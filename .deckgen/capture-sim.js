'use strict';
// Focused capture of the Simulator screen (reuses the zero-dep CDP approach).
const { spawn } = require('child_process');
const fs = require('fs'); const os = require('os'); const path = require('path');
const CHROME = ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(p => fs.existsSync(p));
const APP='http://localhost:3000/'; const OUT=path.join(__dirname,'assets'); const PORT=9334; const VW=1600,VH=1000,SCALE=2;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function getWs(){for(let i=0;i<60;i++){try{const r=await fetch(`http://127.0.0.1:${PORT}/json`);const t=await r.json();const p=t.find(x=>x.type==='page'&&x.webSocketDebuggerUrl);if(p)return p.webSocketDebuggerUrl;}catch(_){}await sleep(500);}throw new Error('no cdp');}
function cdp(ws){let id=0;const pend=new Map();ws.addEventListener('message',ev=>{const m=JSON.parse(ev.data);if(m.id&&pend.has(m.id)){pend.get(m.id)(m);pend.delete(m.id);}});return(method,params={})=>new Promise((res,rej)=>{const i=++id;pend.set(i,m=>m.error?rej(new Error(m.error.message)):res(m.result));ws.send(JSON.stringify({id:i,method,params}));});}
const CLICK=`(label=>{const n=s=>(s||'').replace(/\\s+/g,' ').trim();const e=[...document.querySelectorAll('nav a,nav button,aside a,aside button,[class*=sidebar] a,[class*=sidebar] button')].find(x=>n(x.innerText).toLowerCase().startsWith(label.toLowerCase()));if(e){e.scrollIntoView();e.click();return true}return false})`;
const FREEZE=`(()=>{let s=document.getElementById('__fz');if(!s){s=document.createElement('style');s.id='__fz';document.head.appendChild(s);}s.textContent='*,*::before,*::after{animation:none!important;transition:none!important}';document.querySelectorAll('.animate-spin,.animate-pulse,.animate-ping,.animate-bounce').forEach(e=>e.classList.remove('animate-spin','animate-pulse','animate-ping','animate-bounce'));window.scrollTo(0,0);return 1})()`;
(async()=>{
  const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'cs-'));
  const ch=spawn(CHROME,['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check',`--remote-debugging-port=${PORT}`,`--user-data-dir=${tmp}`,`--window-size=${VW},${VH}`,'--hide-scrollbars','--force-color-profile=srgb','about:blank'],{stdio:'ignore'});
  try{
    const ws=new WebSocket(await getWs());
    await new Promise((r,j)=>{ws.addEventListener('open',r);ws.addEventListener('error',j);});
    const send=cdp(ws);
    await send('Page.enable');await send('Runtime.enable');
    await send('Emulation.setDeviceMetricsOverride',{width:VW,height:VH,deviceScaleFactor:SCALE,mobile:false});
    await send('Page.navigate',{url:APP});
    await sleep(6000);
    const r=await send('Runtime.evaluate',{expression:`${CLICK}("Simulator")`,returnByValue:true});
    if(!r.result||r.result.value!==true){console.log('NAV-MISS');process.exit(2);}
    await sleep(6000);
    await send('Runtime.evaluate',{expression:FREEZE});
    await sleep(600);
    const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
    fs.writeFileSync(path.join(OUT,'simulator.png'),Buffer.from(shot.data,'base64'));
    console.log('OK simulator.png',(fs.statSync(path.join(OUT,'simulator.png')).size/1024).toFixed(0)+'KB');
    ws.close();
  }finally{try{ch.kill();}catch(_){}}
})().catch(e=>{console.error('FATAL',e);process.exit(1);});
