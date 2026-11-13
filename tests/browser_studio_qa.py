"""Focus Studio integration checks. Use an isolated Chrome on port 9237."""
import json
import time
from browser_icon_migration_qa import DevTools, wait_for_browser, APP_URL


def main():
    page = next(p for p in wait_for_browser() if p['type'] == 'page')
    dev = DevTools(page['webSocketDebuggerUrl'])
    def js(code):
        return dev.evaluate(code)
    try:
        dev.call('Page.navigate', {'url': APP_URL})
        time.sleep(1)
        js("document.querySelector('[data-a=closeSheet]')?.click(); document.querySelector('[data-v=studio]').click()")
        time.sleep(.3)
        assert js("!!document.querySelector('#studioSearch')"), 'Studio route'
        js("""new Promise((resolve,reject)=>{const r=indexedDB.open('wtc-focus-studio',1);r.onsuccess=()=>{const tx=r.result.transaction('media','readwrite'),s=tx.objectStore('media');const cursor=s.openCursor();cursor.onsuccess=()=>{const c=cursor.result;if(c){if(c.value.name==='Studio QA.wav')c.delete();c.continue();}};tx.oncomplete=()=>{r.result.close();resolve(true);};tx.onerror=reject;};r.onerror=reject;})""")
        # Import an actual PCM WAV through the file picker change handler.
        js("""(() => {
          const bytes=new ArrayBuffer(16044), v=new DataView(bytes);
          const str=(offset,s)=>[...s].forEach((c,i)=>v.setUint8(offset+i,c.charCodeAt(0)));
          str(0,'RIFF');v.setUint32(4,16036,true);str(8,'WAVE');str(12,'fmt ');
          v.setUint32(16,16,true);v.setUint16(20,1,true);v.setUint16(22,1,true);
          v.setUint32(24,8000,true);v.setUint32(28,16000,true);v.setUint16(32,2,true);v.setUint16(34,16,true);
          str(36,'data');v.setUint32(40,16000,true);
          const transfer=new DataTransfer();transfer.items.add(new File([bytes],'Studio QA.wav',{type:'audio/wav'}));
          const input=[...document.querySelectorAll('input[type=file]')].find(x=>x.accept==='audio/*,video/*');
          input.files=transfer.files;input.dispatchEvent(new Event('change'));return true;
        })()""")
        time.sleep(.6)
        assert js("document.querySelector('#studioTracks').textContent.includes('Studio QA.wav')"), 'Import'
        js("document.querySelector('[data-studio=favorite]').click()")
        time.sleep(.3)
        assert js("document.querySelector('[data-studio=favorite]').getAttribute('aria-pressed')==='true'"), 'Favorite'
        js("document.querySelector('[data-studio=play]').click()")
        time.sleep(.4)
        assert js("!document.querySelector('.studio-dock').hidden && document.querySelector('#studioAudio').duration===1"), 'Decoded WAV'
        js("document.querySelector('[data-v=tasks]').click()")
        assert js("!document.querySelector('.studio-dock').hidden"), 'Navigation retains player'
        js("document.querySelector('[data-v=studio]').click();document.querySelector('[data-studio=timer]').click()")
        time.sleep(1.7)
        assert js("document.querySelector('#studioTimerButton').textContent==='Pause session' && document.querySelector('#studioClock').textContent!=='25:00'"), 'Timer runs'
        js("document.querySelector('[data-studio=timer]').click();document.querySelector('[data-studio=reset]').click()")
        assert js("document.querySelector('#studioClock').textContent==='25:00'"), 'Timer reset'
        for width in (390, 768, 1440):
            dev.call('Emulation.setDeviceMetricsOverride', {'width':width,'height':900,'deviceScaleFactor':1,'mobile':width<600})
            assert js("document.documentElement.scrollWidth<=innerWidth"), f'Overflow at {width}'
        js("location.reload()")
        time.sleep(.8)
        js("document.querySelector('[data-v=studio]').click()")
        time.sleep(.2)
        assert js("document.querySelector('#studioTracks').textContent.includes('Studio QA.wav')"), 'Persisted media'
        assert js("document.querySelector('[data-studio=favorite]').getAttribute('aria-pressed')==='true'"), 'Persisted favorite'
        js("document.querySelector('#studioSearch').value='no-match';document.querySelector('#studioSearch').dispatchEvent(new Event('input',{bubbles:true}))")
        assert js("document.querySelector('#studioTracks').textContent.includes('No matching media')"), 'Search'
        js("document.querySelector('#studioSearch').value='';document.querySelector('#studioSearch').dispatchEvent(new Event('input',{bubbles:true}));window.confirm=()=>true;document.querySelector('[data-studio=remove]').click()")
        time.sleep(.3)
        assert js("!document.querySelector('#studioTracks').textContent.includes('Studio QA.wav')"), 'Remove'
        # Generate a tiny real video locally and exercise the same import path.
        assert js("""new Promise(resolve=>{
          const canvas=document.createElement('canvas');canvas.width=160;canvas.height=90;
          const ctx=canvas.getContext('2d'), stream=canvas.captureStream(10), chunks=[];
          const recorder=new MediaRecorder(stream,{mimeType:'video/webm'});
          recorder.ondataavailable=e=>chunks.push(e.data);
          recorder.onstop=()=>{stream.getTracks().forEach(t=>t.stop());const dt=new DataTransfer();dt.items.add(new File(chunks,'Studio QA.webm',{type:'video/webm'}));const input=[...document.querySelectorAll('input[type=file]')].find(x=>x.accept==='audio/*,video/*');input.files=dt.files;input.dispatchEvent(new Event('change'));resolve(true);};
          recorder.start();ctx.fillStyle='navy';ctx.fillRect(0,0,160,90);setTimeout(()=>recorder.stop(),400);
        })"""), 'Video fixture'
        time.sleep(.5)
        js("document.querySelector('[data-studio=filter][data-kind=video]').click();document.querySelector('[data-studio=play]').click()")
        time.sleep(.5)
        assert js("!document.querySelector('#studioVideo').hidden && document.querySelector('#studioVideo').videoWidth===160"), 'Video decoded'
        js("document.querySelector('[data-studio=close]').click();document.querySelector('[data-studio=remove]').click()")
        time.sleep(.3)
        js("document.querySelector('[data-studio=filter][data-kind=all]').click()")
        dev.call('Emulation.setDeviceMetricsOverride', {'width':1440,'height':1000,'deviceScaleFactor':1,'mobile':False})
        print(json.dumps({'studio':'passed','checks':['import WAV','decode','favorites','navigation playback','focus timer','reset','three viewport widths','reload persistence','search','remove','import and decode video']}))
    finally:
        dev.sock.close()


if __name__ == '__main__':
    main()
