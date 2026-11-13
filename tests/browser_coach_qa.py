"""Run only against an isolated Chrome profile on port 9237."""
import json
import time
from browser_icon_migration_qa import DevTools, wait_for_browser, APP_URL


def main():
    dev = DevTools(next(p for p in wait_for_browser() if p['type'] == 'page')['webSocketDebuggerUrl'])
    def js(code):
        result = dev.evaluate(code)
        time.sleep(.25)  # Allow the app's 200 ms debounced save to commit.
        return result
    def go(view):
        js(f"document.querySelector('[data-a=closeSheet]')?.click();document.querySelector('[data-v={view}]').click()")
    try:
        dev.call('Page.navigate', {'url': APP_URL})
        time.sleep(.6)
        # Upgrade a v5 backup with a locked task and an eligible task today.
        js("""(() => {const d=JSON.parse(localStorage.getItem('wtc.v1'));const now=new Date();const today=[now.getFullYear(),String(now.getMonth()+1).padStart(2,'0'),String(now.getDate()).padStart(2,'0')].join('-');d.v=5;delete d.coach;d.profile.onboarded=true;d.profile.sample=false;d.tasks=[{id:'coach-task',title:'Read a chapter',state:'active',date:today,availabilityPolicy:'anytime',rec:{type:'none'},rem:{on:false}},{id:'locked-task',title:'Locked tomorrow',state:'active',date:'2099-12-31',availabilityPolicy:'scheduled',startTime:'12:00',rec:{type:'none'},rem:{on:false}}];d.occs=[];localStorage.setItem('wtc.v1',JSON.stringify(d));location.reload();return true;})()""")
        time.sleep(.8)
        assert js("document.querySelector('.coach-card').textContent.includes('Read a chapter')"), 'Eligible suggestion'
        js("document.querySelector('[data-a=coachEnergy][data-v=low]').click()")
        assert js("JSON.parse(localStorage.getItem('wtc.v1')).v===6"), 'Migration'
        assert js("document.querySelector('.coach-card').textContent.includes('two gentle minutes')"), 'Energy copy'
        js("document.querySelector('[data-a=coachStart]').click()")
        assert js("document.querySelector('#studioClock').textContent==='02:00'"), 'Two-minute handoff'
        # Advance only the wall clock: completion should ask, never mark a task done.
        js("window.qaRealNow=Date.now;Date.now=()=>window.qaRealNow()+121000")
        time.sleep(.4)
        js("Date.now=window.qaRealNow")
        assert js("document.querySelector('#sheetTitle').textContent.includes('Two minutes')"), 'Completion confirmation'
        js("document.querySelector('#sheetRoot [data-a=coachRecord]').click()")
        state=js("JSON.parse(localStorage.getItem('wtc.v1'))")
        assert len(state['coach']['wins'])==1
        assert all(o['status'] != 'done' for o in state['occs']), 'Small win is not task completion'
        go('home')
        assert js("!document.querySelector('[data-a=coachRecord]')"), 'No duplicate daily win'
        js("document.querySelector('[data-a=coachSharePreview]').click()")
        assert js("!document.querySelector('#sheetRoot').textContent.includes('Read a chapter')"), 'Private sharing preview'
        go('habits')
        js("(() => {document.querySelector('[data-a=coachNewHabit]').click();const f=document.querySelector('[data-form=coachHabit]');f.elements.name.value='Walk outside';f.elements.target.value='3';f.requestSubmit();})()")
        assert js("document.querySelector('.coach-habit').textContent.includes('0 / 3')"), 'Weekly habit'
        js("document.querySelector('[data-a=coachLogHabit]').click()")
        assert js("JSON.parse(localStorage.getItem('wtc.v1')).coach.weeklyHabits[0].dates.length===1"), 'Daily check-in'
        js("document.querySelector('[data-a=coachLogHabit]').click()")
        assert js("JSON.parse(localStorage.getItem('wtc.v1')).coach.weeklyHabits[0].dates.length===0"), 'Undo check-in'
        js("document.querySelector('[data-a=coachLogHabit]').click()")
        go('reports')
        js("(() => {const f=document.querySelector('[data-form=coachReflection]');f.elements.worked.value='A smaller first step';f.elements.hard.value='Late nights';f.elements.next.value='Start earlier';f.requestSubmit();})()")
        assert js("JSON.parse(localStorage.getItem('wtc.v1')).coach.reflections.length===1"), 'Reflection saved'
        js("document.querySelector('[data-form=coachReflection]').requestSubmit()")
        assert js("JSON.parse(localStorage.getItem('wtc.v1')).coach.reflections.length===1"), 'Reflection upsert'
        js("location.reload()")
        time.sleep(.7)
        go('motivation')
        assert js("document.querySelector('.coach-badge.earned').textContent.includes('First step')"), 'Milestone earned'
        assert js("document.querySelector('#coach-worked').value==='A smaller first step'"), 'Reflection persists'
        for width in (390,768,1440):
            dev.call('Emulation.setDeviceMetricsOverride',{'width':width,'height':950,'deviceScaleFactor':1,'mobile':width<600})
            for view in ('home','habits','reports','motivation'):
                go(view)
                assert js('document.documentElement.scrollWidth<=innerWidth'), f'Overflow {view} {width}'
        go('home')
        assert js("[...document.querySelectorAll('img.logo')].every(x=>x.complete&&x.naturalWidth>0)"), 'Icon loads'
        print(json.dumps({'coach':'passed','checks':['v5 migration','energy','eligible task','two-minute timer','confirmation','task status unchanged','daily deduplication','private preview','weekly check-in and undo','reflection upsert and reload','milestones','12 responsive layouts','icon']}))
    finally:
        dev.sock.close()


if __name__ == '__main__':
    main()
