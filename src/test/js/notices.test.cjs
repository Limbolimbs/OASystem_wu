const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '../../main/resources/static');
const work = 'D:/oa-maintenance/notice-20261009';
const jqueryUrl = 'https://code.jquery.com/jquery-3.7.1.min.js';
let browser, jquery;
before(async () => {
    browser = await chromium.launch({headless:true,channel:process.env.OA_BROWSER_CHANNEL || 'msedge'});
    const context=await browser.newContext();
    try { const response=await context.request.get(jqueryUrl); assert.ok(response.ok()); jquery=await response.body(); }
    finally { await context.close(); }
});
after(async()=>{await browser?.close();});
async function pageFor(t, handler) {
    const page=await browser.newPage({viewport:{width:1440,height:1000}});
    const html=fs.readFileSync(path.join(work,'admin-preview.html'),'utf8').replace('</head>', '<meta name="_csrf" content="test-token"><meta name="_csrf_header" content="X-CSRF-TOKEN"></head>');
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    const browserDialogs=[];page.on('dialog',async dialog=>{browserDialogs.push(dialog.type());await dialog.dismiss();});
    await page.route('**/*',async route=>{
        const req=route.request(),url=new URL(req.url());
        if(req.url()===jqueryUrl) return route.fulfill({contentType:'text/javascript',body:jquery});
        if(url.origin!=='https://oa.example.test') return route.abort();
        if(['/css/','/js/'].some(prefix=>url.pathname.startsWith(prefix))) {
            const file=path.join(root,url.pathname);return route.fulfill({contentType:url.pathname.endsWith('.css')?'text/css':'text/javascript',body:fs.readFileSync(file)});
        }
        if(req.resourceType()==='document') return route.fulfill({contentType:'text/html; charset=utf-8',body:html});
        const response=await handler(req,url);
        return response?route.fulfill(response):route.abort();
    });
    await page.goto('https://oa.example.test/notices');
    await page.addStyleTag({path:path.join(root,'css/message-dialog.css')});
    await page.addScriptTag({path:path.join(root,'js/message-dialog.js')});
    await page.waitForFunction(()=>typeof window.jQuery==='function');
    await page.evaluate(()=>new Promise(resolve=>$(document).ready(resolve)));
    t.after(async()=>{await page.close();assert.deepEqual(errors,[]);assert.deepEqual(browserDialogs,[]);});
    return page;
}
const detail={id:1,version:2,title:'社内のお知らせ',content:'<script>alert(1)</script>\n安全な本文',category:'一般',priority:'IMPORTANT',status:'PUBLISHED',author:'管理者',updatedAt:'2026-10-09T12:00:00',deleted:false};
const json=value=>({contentType:'application/json',body:JSON.stringify(value)});
const fragment=(id,content,url='/notices')=>({contentType:'text/html',body:`<div id="${id}" data-ajax-region data-ajax-url="${url}">${content}</div>`});
test('詳細はAJAXで開き、本人既読を登録し、HTML本文を実行しない',async t=>{
    const calls=[];
    const page=await pageFor(t,async(req,url)=>{
        calls.push({method:req.method(),path:url.pathname,data:req.postData(),csrf:req.headers()['x-csrf-token'],transport:req.resourceType()});
        if(url.pathname==='/notices/1') return json(detail);
        if(url.pathname==='/notices/1/read') return json({success:true,message:'既読にしました。'});
        return fragment(url.searchParams.get('fragment'),'更新された表示');
    });
    await page.evaluate(()=>window.sameDocument='kept');
    await page.screenshot({path:path.join(work,'notice-page.png'),fullPage:true});
    await page.click('.notice-view');
    await page.waitForFunction(()=>document.getElementById('noticeReadMessage').textContent.includes('既読にしました'));
    assert.equal(await page.textContent('#noticeDetailBody'),detail.content);
    assert.equal(await page.locator('#noticeDetailBody script').count(),0);
    const read=calls.find(v=>v.path==='/notices/1/read');assert.equal(read.csrf,'test-token');assert.equal(read.data,'version=2');
    assert.ok(calls.every(v=>v.transport==='xhr'));
    assert.equal(await page.evaluate(()=>window.sameDocument),'kept');
    await page.screenshot({path:path.join(work,'notice-detail.png')});
});
test('編集はJSONを入力へ反映し、保存後は局所更新してIDを消す',async t=>{
    let posted;
    const page=await pageFor(t,async(req,url)=>{
        if(url.pathname==='/notices/1') return json(detail);
        if(req.method()==='POST') {posted=req.postData();return json({success:true,message:'お知らせを保存しました。'});}
        return fragment(url.searchParams.get('fragment'),'保存後の表示');
    });
    await page.click('.notice-edit');await page.waitForFunction(()=>document.querySelector('[name="id"]').value==='1');
    assert.equal(await page.inputValue('[name="version"]'),'2');
    await page.fill('[name="content"]','編集した本文');await page.click('#noticeForm button[type="submit"]');
    await page.waitForFunction(()=>document.querySelector('[name="id"]').value==='' && document.getElementById('noticeForm').getAttribute('aria-busy')!== 'true');
    assert.ok(posted.includes('id=1') && posted.includes('version=2'));assert.equal(await page.inputValue('[name="content"]'),'');
});
test('一覧再取得は現在の検索条件を維持し、本文草稿を残す',async t=>{
    const calls=[];
    const page=await pageFor(t,async(req,url)=>{calls.push(url);return fragment(url.searchParams.get('fragment'),'検索後の表示','/notices?keyword=abc&filter=PUBLISHED');});
    await page.fill('[name="content"]','入力中の草稿');
    await page.evaluate(()=>document.getElementById('notice-data').dataset.ajaxUrl='/notices?keyword=abc&filter=PUBLISHED&page=2');
    await page.click('#noticeRefresh');await page.waitForFunction(()=>document.getElementById('notice-data').textContent.includes('検索後'));
    assert.ok(calls.every(url=>url.searchParams.get('keyword')==='abc'&&url.searchParams.get('filter')==='PUBLISHED'));
    assert.equal(await page.inputValue('[name="content"]'),'入力中の草稿');
});

test('スマートフォン幅でも入力と詳細が画面内に収まる',async t=>{
    const page=await pageFor(t,async(req,url)=>{
        if(url.pathname==='/notices/1') return json(detail);
        if(url.pathname==='/notices/1/read') return json({success:true});
        return fragment(url.searchParams.get('fragment'),'更新された表示');
    });
    await page.setViewportSize({width:390,height:844});
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));
    await page.screenshot({path:path.join(work,'notice-mobile.png'),fullPage:true});
    await page.click('.notice-view');
    await page.waitForFunction(()=>document.getElementById('noticeDetail').open);
    const box=await page.locator('#noticeDetail').boundingBox();
    assert.ok(box.x>=0 && box.x+box.width<=390);
    await page.screenshot({path:path.join(work,'notice-mobile-detail.png')});
});
