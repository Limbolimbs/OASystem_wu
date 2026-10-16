// 合成ページのみ。実サイト・DB・アカウントに接続せず、動作と停止条件を確認する。
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const assets = path.resolve(__dirname, '../../main/resources/static');
const preview = process.env.OA_UI_PREVIEW || path.resolve(__dirname, '../../../target/ui-preview');
const evidence = process.env.OA_UI_EVIDENCE || path.resolve(__dirname, '../../../target/ui-feedback');
const cdn = 'https://code.jquery.com/jquery-3.7.1.min.js';
let browser, jquery;
before(async () => {
    browser = await chromium.launch({headless:true, channel:process.env.OA_BROWSER_CHANNEL || 'msedge'});
    const context = await browser.newContext();
    try { const result = await context.request.get(cdn); assert.ok(result.ok()); jquery = await result.body(); }
    finally { await context.close(); }
    fs.mkdirSync(evidence, {recursive:true});
});
after(async () => browser?.close());
const fixture = `<!doctype html><html lang="ja"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width">
<link rel="stylesheet" href="/css/portal-layout.css"><link rel="stylesheet" href="/css/portal-components.css">
<link rel="stylesheet" href="/css/message-dialog.css"><link rel="stylesheet" href="/css/interaction-feedback.css">
<script src="/js/interaction-feedback.js" defer></script></head><body><main class="content">
<h1 id="panel">操作の確認</h1><button id="trigger" class="business-submit" type="button" aria-label="保存"><span>保存する</span></button>
<section id="region"><label>入力<input id="draft" value="保持する入力"></label></section></main>
<script src="${cdn}"></script><script src="/js/message-dialog.js"></script></body></html>`;

async function setup(t, options = {}) {
    const context = await browser.newContext({viewport:{width:1280,height:840},
        reducedMotion:options.reduce ? 'reduce' : 'no-preference',
        ...(options.record ? {recordVideo:{dir:evidence, size:{width:960,height:630}}} : {})});
    const page = await context.newPage();
    const errors = [], nativeDialogs = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('dialog', async dialog => {nativeDialogs.push(dialog.type()); await dialog.dismiss();});
    await page.route('**/*', async route => {
        const request=route.request(), url=new URL(request.url());
        if (request.url()===cdn) return route.fulfill({contentType:'text/javascript',body:jquery});
        if (url.pathname==='/test') return route.fulfill({contentType:'text/html; charset=utf-8',body:fixture});
        if (request.method()==='GET' && /^\/(css|js)\/[a-z0-9-]+\.(css|js)$/.test(url.pathname)) {
            return route.fulfill({contentType:url.pathname.endsWith('.css')?'text/css':'text/javascript',body:fs.readFileSync(path.join(assets,url.pathname.slice(1)))});
        }
        const response=await options.handler?.(request,url);
        if (response) return route.fulfill(response);
        return route.abort();
    });
    await page.goto('https://motion.example.test'+(options.start || '/test'));
    await page.waitForFunction(() => window.PortalFeedback && window.PortalDialog);
    t.after(async () => {
        const video=page.video();
        await context.close();
        if (options.record && video) fs.renameSync(await video.path(), path.join(evidence,'ui-feedback-demo.webm'));
        assert.deepEqual(errors,[]); assert.deepEqual(nativeDialogs,[]);
    });
    return page;
}

test('処理中の表示を重複させず、元の要素・ARIA・幅・クリック処理を復帰する', async t => {
    const page=await setup(t);
    const result=await page.evaluate(() => {
        const button=document.getElementById('trigger'), child=button.firstChild;
        button.style.minWidth='80px';
        let clicks=0; child.addEventListener('click',()=>clicks++);
        const end=PortalFeedback.busy(button,'保存中');
        const same=end===PortalFeedback.busy(button,'別の表示');
        const active=button.getAttribute('aria-busy');
        const count=button.querySelectorAll('.button-spinner').length;
        end(); end(); child.click();
        return {same,active,count,restored:button.firstChild===child,width:button.style.minWidth,
            aria:button.getAttribute('aria-busy'),label:button.getAttribute('aria-label'),clicks};
    });
    assert.deepEqual(result,{same:true,active:'true',count:1,restored:true,width:'80px',aria:null,label:'保存',clicks:1});
});

test('確認中・退出中の連続操作でも確認を増やさず、次の確認を誤って閉じない', async t => {
    const page=await setup(t);
    await page.focus('#trigger');
    const result=await page.evaluate(async () => {
        const first=PortalDialog.confirm('最初の確認');
        document.querySelector('.message-dialog-accept').click();
        const blocked=await PortalDialog.confirm('退出中の重複');
        const accepted=await first;
        const focus=document.activeElement.id;
        const next=PortalDialog.confirm('次の確認');
        document.querySelector('.message-dialog-cancel').click();
        return {blocked,accepted,focus,canceled:await next,open:document.querySelector('dialog').open};
    });
    assert.deepEqual(result,{blocked:false,accepted:true,focus:'trigger',canceled:false,open:false});
});

test('動作軽減では入場・波紋・回転を実行せず、確認とフォーカス復帰は維持する', async t => {
    const page=await setup(t,{reduce:true});
    await page.focus('#trigger');
    const result=await page.evaluate(async () => {
        const button=document.getElementById('trigger');
        PortalFeedback.reveal(document.getElementById('panel'));
        const animationCount=document.getElementById('panel').getAnimations().length;
        const end=PortalFeedback.busy(button);
        const spinner=getComputedStyle(button.querySelector('.button-spinner')).animationName;
        end();
        const answer=PortalDialog.confirm('動作軽減'); document.querySelector('.message-dialog-cancel').click();
        return {animationCount,spinner,answer:await answer,focus:document.activeElement.id};
    });
    assert.deepEqual(result,{animationCount:0,spinner:'none',answer:false,focus:'trigger'});
});

test('動作APIが利用できなくても確認を終了し、ボタン操作を継続できる', async t => {
    const page=await setup(t);
    await page.focus('#trigger');
    const result=await page.evaluate(async () => {
        Element.prototype.animate=undefined;
        const answer=PortalDialog.confirm('代替動作'); document.querySelector('.message-dialog-cancel').click();
        return {answer:await answer,open:document.querySelector('dialog').open,focus:document.activeElement.id};
    });
    assert.deepEqual(result,{answer:false,open:false,focus:'trigger'});
});

test('非表示になった画面の演出を中止し、処理中アイコンの回転も停止する', async t => {
    const page=await setup(t);
    const result=await page.evaluate(() => {
        const panel=document.getElementById('panel');
        PortalFeedback.reveal(panel);
        PortalFeedback.busy(document.getElementById('trigger'));
        Object.defineProperty(document,'hidden',{configurable:true,get:()=>true});
        document.dispatchEvent(new Event('visibilitychange'));
        return {animations:panel.getAnimations().length,spinner:getComputedStyle(document.querySelector('.button-spinner')).animationPlayState};
    });
    assert.deepEqual(result,{animations:0,spinner:'paused'});
});

test('部分更新時に入力中の領域を動かさず、フォーカスと入力を保持する', async t => {
    const page=await setup(t);
    await page.fill('#draft','変更中の入力');
    const result=await page.evaluate(() => {
        const region=document.getElementById('region');
        document.dispatchEvent(new CustomEvent('ajax:updated',{detail:{region}}));
        return {value:document.getElementById('draft').value,focus:document.activeElement.id,animations:region.getAnimations().length};
    });
    assert.deepEqual(result,{value:'変更中の入力',focus:'draft',animations:0});
});

test('波紋は1個に制限され、終了後にDOMを残さない', async t => {
    const page=await setup(t);
    await page.evaluate(() => {
        const button=document.getElementById('trigger');
        for(let i=0;i<4;i++)button.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,clientX:20,clientY:20}));
    });
    assert.equal(await page.locator('.button-ripple').count(),1);
    await page.waitForFunction(() => document.querySelectorAll('.button-ripple').length===0);
});

test('時差入場は待機中も開始状態を保ち、入場前の点滅を防ぐ', async t => {
    const page=await setup(t);
    const result=await page.evaluate(() => {
        const panel=document.getElementById('panel');
        PortalFeedback.reveal(panel,{delay:200});
        const animation=panel.getAnimations()[0];
        animation.pause(); animation.currentTime=0;
        return {opacity:Number(getComputedStyle(panel).opacity),fill:animation.effect.getTiming().fill};
    });
    assert.deepEqual(result,{opacity:0,fill:'backwards'});
});

test('メッセージ挿入で親子を二重移動せず、連続更新後も座標を復帰する', async t => {
    const page=await setup(t);
    const result=await page.evaluate(async () => {
        const root=document.createElement('div');
        root.style.cssText='position:absolute;top:200px;left:20px;width:300px';
        const label=document.createElement('p'); label.textContent='操作'; root.append(label); document.body.append(root);
        const before=label.getBoundingClientRect().top;
        PortalFeedback.layout([root,label],()=>{root.style.top='260px';label.style.marginTop='40px';});
        for(const el of [root,label])for(const a of el.getAnimations()){a.pause();a.currentTime=0;}
        const first=label.getBoundingClientRect().top;
        PortalFeedback.layout([root,label],()=>{root.style.top='240px';label.style.marginTop='16px';});
        for(const el of [root,label])for(const a of el.getAnimations()){a.pause();a.currentTime=0;}
        const second=label.getBoundingClientRect().top;
        for(const el of [root,label])for(const a of el.getAnimations())a.finish();
        await new Promise(resolve=>requestAnimationFrame(resolve));
        return {first:Math.abs(first-before),second:Math.abs(second-first),end:label.getBoundingClientRect().top};
    });
    assert.ok(result.first<1); assert.ok(result.second<1); assert.equal(result.end,256);
});

test('入場途中の取消は現在の濃度から退出し、濃度1へ飛ばない', async t => {
    const page=await setup(t);
    const result=await page.evaluate(async () => {
        const answer=PortalDialog.confirm('連続動作の確認');
        const dialog=document.querySelector('dialog');
        const entrance=dialog.getAnimations().find(a=>a.animationName==='dialog-enter');
        entrance.pause(); entrance.currentTime=50;
        const before=Number(getComputedStyle(dialog).opacity);
        document.querySelector('.message-dialog-cancel').click();
        const exit=dialog.getAnimations().find(a=>!a.animationName);
        exit.pause(); exit.currentTime=0;
        const after=Number(getComputedStyle(dialog).opacity);
        exit.finish();
        return {before,after,answer:await answer};
    });
    assert.ok(result.before<1); assert.ok(Math.abs(result.before-result.after)<.01); assert.equal(result.answer,false);
});

test('実テンプレートの処理中・取消復帰・エラーを合成応答で撮影する', async t => {
    const loginFile=path.join(preview,'login.html'), profileFile=path.join(preview,'profile-employee.html');
    if(!fs.existsSync(loginFile)||!fs.existsSync(profileFile))return t.skip('先にMVC試験でプレビューを出力する');
    let posts=0;
    const page=await setup(t,{record:true,start:'/',handler:async(request,url)=>{
        if(request.method()==='GET' && url.pathname==='/')return {contentType:'text/html; charset=utf-8',body:fs.readFileSync(loginFile,'utf8')};
        if(request.method()==='GET' && url.pathname==='/profile')return {contentType:'text/html; charset=utf-8',body:fs.readFileSync(profileFile,'utf8')};
        if(request.method()==='POST'){
            posts++; await new Promise(resolve=>setTimeout(resolve,900));
            return url.pathname==='/users/login'?{contentType:'text/plain',body:'NG'}:
                {status:400,contentType:'application/json',body:JSON.stringify({success:false,message:'画面テスト用の応答です。パスワードは変更していません。'})};
        }
    }});
    await page.fill('#userName','EMP-DEMO'); await page.fill('#password','dummy-input');
    await page.hover('.login-button'); await page.click('.login-button');
    await page.waitForSelector('.login-button.ui-pending');
    await page.screenshot({path:path.join(evidence,'login-pending.png')});
    await page.waitForFunction(()=>!document.querySelector('.error-message').hidden);
    await page.waitForFunction(()=>!document.querySelector('.login-button').disabled);
    assert.equal(await page.locator('.button-spinner').count(),0);
    await page.goto('https://motion.example.test/profile');
    await page.waitForFunction(()=>!document.querySelector('#passwordForm [type=submit]').disabled);
    await page.fill('[name=currentPassword]','dummy-current'); await page.fill('[name=newPassword]','DemoSecret123!');
    await page.fill('[name=passwordConfirmation]','DemoSecret123!');
    await page.click('#passwordForm [type=submit]'); await page.waitForSelector('dialog[open]');
    await page.screenshot({path:path.join(evidence,'password-confirm.png')});
    await page.keyboard.press('Escape'); await page.waitForFunction(()=>!document.querySelector('dialog').open);
    assert.equal(await page.evaluate(()=>document.activeElement.type),'submit');
    await page.click('#passwordForm [type=submit]'); await page.click('.message-dialog-accept');
    await page.waitForSelector('#passwordForm .button-spinner');
    await page.waitForFunction(()=>!document.getElementById('password-message').hidden);
    assert.equal(posts,2);
    assert.equal(await page.locator('#passwordForm .button-spinner').count(),0);
    await page.setViewportSize({width:390,height:844});
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    await page.screenshot({path:path.join(evidence,'profile-mobile.png'),fullPage:true});
});
