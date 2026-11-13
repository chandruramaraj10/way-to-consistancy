/* Device-local media lives separately from the JSON task backup. No remote requests. */
(() => {
  'use strict';
  const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const q = s => document.querySelector(s);
  let library = [], filter = 'all', search = '', current = null, objectUrl = '', connection;
  let selectedMinutes = 25, remaining = 1500, deadline = 0, timerRunning = false, timerComplete = false;
  let focusToken = null;
  window.WtcStudio = {isRunning: () => timerRunning};
  document.addEventListener('wtc:focus-start', e => {
    if(timerRunning || e.detail?.minutes !== 2 || typeof e.detail?.token !== 'string')return;
    focusToken=e.detail.token;selectedMinutes=2;remaining=120;deadline=Date.now()+120000;timerRunning=true;timerComplete=false;mount();
  });
  let loadError = '', busy = false, playGeneration = 0;
  const dock = document.createElement('section');
  dock.className = 'studio-dock'; dock.hidden = true; dock.setAttribute('aria-label','Media player');
  dock.innerHTML = '<div class="studio-dock-head"><strong id="studioNow">Your soundtrack</strong><span class="studio-timer-strip" id="studioMiniTimer"></span><button class="btn small" data-studio="next" aria-label="Play next track">Next</button><button class="btn small" data-studio="repeat" aria-pressed="false">Repeat</button><button class="btn small" data-studio="close" aria-label="Close player">Close</button></div><audio id="studioAudio" controls preload="metadata" hidden></audio><video id="studioVideo" controls playsinline preload="metadata" hidden></video><div class="studio-live" id="studioPlayerStatus" role="status"></div>';
  document.body.append(dock);
  const audio = q('#studioAudio'), video = q('#studioVideo');
  const picker = document.createElement('input'); picker.type='file'; picker.accept='audio/*,video/*'; picker.multiple=true; picker.hidden=true; document.body.append(picker);
  function dbOpen(){
    if (!connection) connection = new Promise((resolve,reject) => {
      const request = indexedDB.open('wtc-focus-studio',1);
      request.onupgradeneeded = () => request.result.createObjectStore('media',{keyPath:'id'});
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error('Close other app tabs and try again.'));
    });
    return connection;
  }
  async function storage(mode, action){
    const db = await dbOpen();
    return new Promise((resolve,reject) => {
      const tx = db.transaction('media', mode), request = action(tx.objectStore('media'));
      tx.oncomplete = () => resolve(request.result);
      tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error || new Error('Storage interrupted'));
    });
  }
  const time = seconds => Math.floor(seconds/60).toString().padStart(2,'0')+':'+Math.floor(seconds%60).toString().padStart(2,'0');
  function say(message){ const el=q('#studioStatus'); if(el) el.textContent=message; }
  async function refresh(){
    try { library=(await storage('readonly',s=>s.getAll())).map(({blob,...meta})=>meta); loadError=''; }
    catch(e){loadError='The media library could not open. Check device storage or try a normal browser window.';}
    renderList();
  }
  function renderList(){
    const target=q('#studioTracks'); if(!target)return;
    const items=library.filter(x=>(filter==='all'||filter==='favorites'&&x.favorite||x.kind===filter)&&x.name.toLowerCase().includes(search.toLowerCase()));
    q('#studioCount').textContent=library.length+' saved · '+(library.reduce((n,x)=>n+x.size,0)/1048576).toFixed(1)+' MB';
    if(loadError){target.innerHTML='<div class="studio-empty"><h3>Library unavailable</h3><p>'+escape(loadError)+'</p><button class="btn" data-studio="reload">Try again</button></div>';return;}
    target.innerHTML=items.length?items.map(x=>'<article class="studio-track"><button class="studio-play" data-studio="play" data-id="'+x.id+'" aria-label="Play '+escape(x.name)+'">▶</button><div><span class="studio-track-title">'+escape(x.name)+'</span><small>'+ (x.kind==='audio'?'Music & audio':'Motivational video')+' · '+(x.size/1048576).toFixed(1)+' MB'+(current===x.id?' · In player':'')+'</small></div><div class="studio-track-actions"><button class="btn small" data-studio="favorite" data-id="'+x.id+'" aria-label="'+(x.favorite?'Unfavorite':'Favorite')+' '+escape(x.name)+'" aria-pressed="'+!!x.favorite+'">'+(x.favorite?'★':'☆')+'</button><button class="btn small" data-studio="download" data-id="'+x.id+'" aria-label="Save original '+escape(x.name)+'">Save</button><button class="btn small" data-studio="remove" data-id="'+x.id+'" aria-label="Remove '+escape(x.name)+'">Remove</button></div></article>').join(''):'<div class="studio-empty"><span class="studio-empty-mark" aria-hidden="true">♫</span><h3>'+(library.length?'No matching media':'A soundtrack for your next step')+'</h3><p>'+(library.length?'Try another filter or search.':'Bring your favorite music, talks, and motivational videos. Your library stays on this device.')+'</p><button class="btn primary" data-studio="import">Import music or video</button></div>';
  }
  function mount(){
    const root=q('#studioView'); if(!root)return;
    root.innerHTML='<header class="studio-header"><span class="studio-eyebrow">YOUR SPACE TO RECHARGE</span><h1>Find your rhythm.</h1><p>A good soundtrack. One clear intention. A small step forward. Make this time yours.</p></header><div class="studio-layout"><section class="studio-library" aria-label="Media library"><div class="studio-toolbar"><div><h2>Your collection</h2><p class="sub" id="studioCount">Loading saved media…</p></div><button class="btn primary" data-studio="import">+ Add media</button></div><div class="studio-filters" role="group" aria-label="Filter media">'+[['all','Everything'],['audio','Music'],['video','Videos'],['favorites','Favorites']].map(([key,label])=>'<button class="btn small" data-studio="filter" data-kind="'+key+'" aria-pressed="'+(filter===key)+'">'+label+'</button>').join('')+'</div><label class="sr" for="studioSearch">Search your media</label><input class="studio-search" id="studioSearch" type="search" placeholder="Find a track, talk, or video…" value="'+escape(search)+'"><p id="studioStatus" class="studio-live" role="status"></p><div id="studioTracks"></div><p class="studio-footnote">Offline, on this device. Up to 100 MB per file. Browser storage can be cleared or evicted; keep your originals. Media is separate from task backups. Use Save to download an original.</p></section><div class="stack" style="gap:22px"><section class="studio-focus" aria-label="Focus timer"><span class="studio-eyebrow">ONE THING AT A TIME</span><h2>Your next focused moment</h2><p class="sub">Choose a session. Let everything else wait.</p><div class="studio-presets">'+[5,15,25,50].map(n=>'<button class="btn small" data-studio="duration" data-minutes="'+n+'" aria-pressed="'+(selectedMinutes===n)+'">'+n+' min</button>').join('')+'</div><div class="studio-clock" id="studioClock" role="timer" aria-label="Time remaining">'+time(remaining)+'</div><div class="rowf"><button class="btn primary" id="studioTimerButton" data-studio="timer">'+(timerRunning?'Pause session':timerComplete?'Start again':'Start focus')+'</button><button class="btn" data-studio="reset">Reset</button></div><p id="studioTimerStatus" class="studio-live" role="status">'+(timerComplete?'Session complete. Take a breath.':timerRunning?'One small step at a time.':'Ready when you are.')+'</p><div class="studio-note">Music keeps playing as you move around the app. The timer follows elapsed time while this page stays open; closing the app resets it.</div></section><aside class="studio-manifesto"><span class="studio-eyebrow">A GENTLE REMINDER</span><h3>Progress has a quiet side.</h3><p>You do not need to do everything today. Choose one thing worth doing, and give it your attention.</p><button class="btn" data-a="goView" data-v="tasks" style="margin-top:16px">Choose your next task</button></aside></div></div>';
    renderList();
  }
  function stop(){
    playGeneration++; audio.pause();video.pause();
    [audio,video].forEach(el=>{el.removeAttribute('src');el.load();el.hidden=true;});
    if(objectUrl)URL.revokeObjectURL(objectUrl);objectUrl='';current=null;dock.hidden=true;document.body.classList.remove('studio-playing');renderList();
  }
  async function play(id){
    stop(); const generation=playGeneration;
    const item=await storage('readonly',s=>s.get(id)); if(!item||generation!==playGeneration)return;
    current=id;objectUrl=URL.createObjectURL(item.blob);
    document.dispatchEvent(new CustomEvent('wtc:studio-play'));
    const el=item.kind==='audio'?audio:video;el.src=objectUrl;el.hidden=false;dock.hidden=false;
    document.body.classList.add('studio-playing');q('#studioNow').textContent=item.name;q('#studioPlayerStatus').textContent='';
    // Stop the older run soundtrack if it is playing through another audio element.
    document.querySelectorAll('audio,video').forEach(other=>{if(other!==el)other.pause();});
    try{await el.play();}catch(e){if(generation===playGeneration)q('#studioPlayerStatus').textContent='Tap Play to start. If playback fails, try an MP3 audio or MP4 video file.';}
    renderList();
  }
  function next(){const items=library.filter(x=>x.kind===library.find(v=>v.id===current)?.kind);const index=items.findIndex(x=>x.id===current);if(index>=0&&index+1<items.length)return play(items[index+1].id);q('#studioPlayerStatus').textContent='You reached the end of your collection.';}
  [audio,video].forEach(el=>{
    el.addEventListener('ended',()=>{Promise.resolve(next()).catch(()=>{q('#studioPlayerStatus').textContent='Could not load the next track.';});});
    el.addEventListener('error',()=>{if(current)q('#studioPlayerStatus').textContent='This format cannot play on this device. Try MP3 audio or MP4 video.';});
  });
  picker.addEventListener('change',async()=>{
    if(busy)return;busy=true;let added=0,failed=0;
    const files=Array.from(picker.files);picker.value='';say('Saving your media…');
    for(const file of files){
      if(!/^(audio|video)\//.test(file.type)||file.size>100*1048576||file.size===0){failed++;continue;}
      try{await storage('readwrite',s=>s.put({id:crypto.randomUUID?crypto.randomUUID():Date.now().toString(36)+Math.random().toString(36).slice(2),name:file.name,kind:file.type.split('/')[0],size:file.size,favorite:false,blob:file}));added++;}catch(e){failed++;}
    }
    busy=false;await refresh();say(added+' file'+(added===1?'':'s')+' saved.'+(failed?' '+failed+' not saved. Use audio/video under 100 MB and check free storage.':''));
  });
  document.addEventListener('input',e=>{if(e.target.id==='studioSearch'){search=e.target.value;renderList();}});
  document.addEventListener('click',async e=>{
    const b=e.target.closest('[data-studio]');if(!b)return;
    try{switch(b.dataset.studio){
      case 'import':if(!busy)picker.click();break;
      case 'reload':connection=null;await refresh();break;
      case 'play':await play(b.dataset.id);break;
      case 'close':stop();break;
      case 'next':await next();break;
      case 'repeat':audio.loop=video.loop=!audio.loop;b.setAttribute('aria-pressed',String(audio.loop));break;
      case 'filter':filter=b.dataset.kind;mount();break;
      case 'favorite':{const item=await storage('readonly',s=>s.get(b.dataset.id));if(item){item.favorite=!item.favorite;await storage('readwrite',s=>s.put(item));await refresh();}break;}
      case 'remove':{const item=library.find(x=>x.id===b.dataset.id);if(!item||!confirm('Remove “'+item.name+'” from this device library? Your original file is unaffected.'))break;if(current===item.id)stop();await storage('readwrite',s=>s.delete(item.id));await refresh();say('Removed from this library. Your original file is unaffected.');break;}
      case 'download':{const item=await storage('readonly',s=>s.get(b.dataset.id));if(!item)break;const url=URL.createObjectURL(item.blob),link=document.createElement('a');link.href=url;link.download=item.name;link.click();setTimeout(()=>URL.revokeObjectURL(url),60000);break;}
      case 'duration':if(timerRunning){say('Pause or reset your session before changing its length.');break;}focusToken=null;selectedMinutes=Number(b.dataset.minutes);remaining=selectedMinutes*60;timerComplete=false;mount();break;
      case 'timer':if(timerRunning){remaining=Math.max(0,Math.ceil((deadline-Date.now())/1000));timerRunning=false;}else{if(remaining<=0)remaining=selectedMinutes*60;deadline=Date.now()+remaining*1000;timerRunning=true;timerComplete=false;}mount();break;
      case 'reset':focusToken=null;timerRunning=false;timerComplete=false;remaining=selectedMinutes*60;mount();break;
    }}catch(error){say('That action could not finish. Check available storage and try again.');q('#studioPlayerStatus').textContent='Unable to load media. Please try again.';}
  });
  setInterval(()=>{
    if(timerRunning){remaining=Math.max(0,Math.ceil((deadline-Date.now())/1000));if(!remaining){timerRunning=false;timerComplete=true;audio.pause();video.pause();mount();const token=focusToken;focusToken=null;if(token)document.dispatchEvent(new CustomEvent('wtc:focus-complete',{detail:{token}}));}}
    const el=q('#studioClock');if(el)el.textContent=time(remaining);
    q('#studioMiniTimer').textContent=timerRunning?time(remaining)+' focus':'';
  },250);
  document.addEventListener('wtc:view',mount);
  document.addEventListener('wtc:run-music',()=>{audio.pause();video.pause();});
  window.addEventListener('pagehide',()=>{audio.pause();video.pause();});
  refresh();mount();
})();
