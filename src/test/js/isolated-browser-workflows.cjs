// JUnitの専用MySQL・一時HTTPサーバーだけを利用する実ブラウザー受入試験。
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const base=process.argv[2],date=process.argv[3];
const parsed=new URL(base);
assert.equal(process.env.OA_ACCEPTANCE_TEST,'true');
assert.equal(parsed.hostname,'127.0.0.1');assert.equal(parsed.protocol,'http:');
assert.equal(parsed.port,process.env.OA_QA_PORT);assert.ok(!['8080','9443','3306'].includes(parsed.port));
const output='D:/oa-maintenance/business-acceptance-20261015/browser';
const errors=[],dialogs=[],pages=[];
let browser;
async function capture(page,name,locator){
 await page.evaluate(()=>Promise.all(document.getAnimations().filter(a=>a.effect?.getTiming().iterations!==Infinity).map(a=>a.finished.catch(()=>{}))));
 await (locator||page).screenshot({path:path.join(output,name),...(locator?{}:{fullPage:true})});
}
async function logged(actor,destination){
 const context=await browser.newContext({viewport:{width:1440,height:1000}});
 const page=await context.newPage();pages.push(page);page.on('pageerror',error=>errors.push(error.message));page.on('dialog',async dialog=>{dialogs.push(dialog.type());await dialog.dismiss();});
 await page.goto(base+'/');await page.waitForFunction(()=>typeof window.jQuery==='function');
 await page.locator('#userName').fill('QA_EMP_'+actor);await page.locator('#password').fill('QaDemoPass123!');
 await Promise.all([page.waitForURL(base+destination),page.locator('.login-button').click()]);
 return page;
}
async function submit(page,selector,endpoint){
 console.log('SUBMIT',endpoint);
 const response=page.waitForResponse(r=>r.url()===base+endpoint&&r.request().method()==='POST');
 await page.locator(selector+' button[type="submit"]').click();assert.equal((await response).status(),200);
 await page.waitForFunction(()=>window.jQuery.active===0&&!document.querySelector('[aria-busy="true"]'));
 console.log('UPDATED',endpoint);
}
(async()=>{
 await fs.mkdir(output,{recursive:true});browser=await chromium.launch({headless:true,channel:process.env.OA_BROWSER_CHANNEL||'msedge'});
 const employee=await logged(3,'/attendance');const ga=await logged(2,'/attendance');const admin=await logged(1,'/home');const sales=await logged(4,'/sales');
 for(const [page,allowed]of [[employee,false],[ga,false],[admin,true],[sales,false]]){
  const response=await page.request.get(base+'/users?fragment=employee-data',{headers:{'X-Requested-With':'XMLHttpRequest'}});assert.equal(response.status(),allowed?200:403);
 }
 await employee.evaluate(()=>window.qaDocumentMarker='same-document');
 assert.equal(await employee.locator('#attendance-overview .attendance-card').nth(2).locator('.attendance-value').textContent(),'未出勤');
 for(const action of ['clock-in','break-start','break-end','clock-out']){
  const selector=`form[action="/attendance/${action}"]`;
  await employee.waitForFunction(s=>{const button=document.querySelector(s+' button');return button&&!button.disabled;},selector);
  await submit(employee,selector,'/attendance/'+action);assert.equal(await employee.evaluate(()=>window.qaDocumentMarker),'same-document');
  assert.equal(await employee.locator('#attendance-overview .attendance-card').nth(2).locator('.attendance-value').textContent(),action==='break-start'?'休憩中':action==='clock-out'?'退勤済み':'勤務中');
 }
 await capture(employee,'employee-attendance.png');
 await employee.evaluate(()=>{
  const item=document.querySelector('#attendance-overview .attendance-card:nth-child(3) .attendance-value');item.style.outline='2px solid #dc2626';item.style.outlineOffset='3px';
  const label=document.createElement('span');label.textContent='項目28';label.style.cssText='display:block;color:#b91c1c;font-size:13px;font-weight:700;margin-bottom:8px';item.before(label);
 });
 await capture(employee,'attendance-state-item28.png',employee.locator('#attendance-overview'));
 await employee.goto(base+'/attendance/manage');const form='form[action="/attendance/manage/requests"]';
 await employee.locator(form+' [name="type"]').selectOption('REMOTE');await employee.locator(form+' [name="startDate"]').fill(date);await employee.locator(form+' [name="endDate"]').fill(date);await employee.locator(form+' [name="reason"]').fill('実ブラウザーの隔離申請試験');
 await employee.evaluate(()=>window.qaDocumentMarker='application-document');await submit(employee,form,'/attendance/manage/requests');await employee.waitForFunction(()=>document.querySelector('#history').textContent.includes('実ブラウザーの隔離申請試験'));assert.equal(await employee.evaluate(()=>window.qaDocumentMarker),'application-document');
 await ga.goto(base+'/attendance/manage');const approval=ga.locator('#review form[action$="/approve"]').first();await approval.locator('[name="comment"]').fill('実ブラウザーで確認');const action=await approval.getAttribute('action');
 await submit(ga,`form[action="${action}"]`,action);await ga.waitForFunction(()=>document.querySelector('#reviewed').textContent.includes('実ブラウザーで確認'));
 await employee.goto(base+'/attendance/manage');assert.ok((await employee.locator('#history').innerText()).includes('承認'));await capture(employee,'employee-request-approved.png');
 await sales.locator('#salesForm [name="salesAmount"]').fill('12345.67');await sales.locator('#salesForm [name="orderCount"]').fill('12');await sales.locator('#salesForm [name="customerCount"]').fill('4');await sales.locator('#salesForm [name="confirmed"]').check();await sales.evaluate(()=>window.qaDocumentMarker='sales-document');
 await submit(sales,'#salesForm','/sales/save');await sales.waitForFunction(()=>document.querySelector('#sales-data').textContent.includes('12,345.67'));assert.equal(await sales.evaluate(()=>window.qaDocumentMarker),'sales-document');
 const dashboard=await admin.request.get(base+'/api/dashboard');assert.equal(dashboard.status(),200);assert.equal((await dashboard.json()).summary.totalSales,12345.67);await admin.reload();await capture(admin,'admin-dashboard.png');
 await ga.goto(base+'/notices');await ga.locator('#noticeForm [name="title"]').fill('隔離ブラウザーのお知らせ');await ga.locator('#noticeForm [name="content"]').fill('<script>window.qaUnsafeScript=true</script>');await ga.locator('#noticeForm [name="status"]').selectOption('PUBLISHED');await submit(ga,'#noticeForm','/notices/save');
 await employee.goto(base+'/notices');assert.equal(await employee.locator('#noticeForm').count(),0);await employee.locator('.notice-view').filter({hasText:'隔離ブラウザーのお知らせ'}).click();await employee.waitForFunction(()=>document.querySelector('#noticeReadMessage').textContent.includes('既読にしました'));
 assert.equal(await employee.locator('#noticeDetailBody script').count(),0);assert.equal(await employee.evaluate(()=>window.qaUnsafeScript),undefined);await capture(employee,'employee-notice.png');
 assert.deepEqual(errors,[]);assert.deepEqual(dialogs,[]);
 await fs.writeFile(path.join(output,'result.json'),JSON.stringify({passed:true,roles:['ADMIN','GENERAL_AFFAIRS','EMPLOYEE','sales-enabled EMPLOYEE'],flows:['login','permission','punch/break','request/approve','sales/dashboard','notice/read/XSS'],pageErrors:errors,browserDialogs:dialogs},null,2));
 console.log('ISOLATED_BROWSER_WORKFLOWS_PASSED');
})().catch(async error=>{console.error(error);console.error('PAGE_ERRORS',errors,'BROWSER_DIALOGS',dialogs);for(const [index,page]of pages.entries()){await page.screenshot({path:path.join(output,'failure-'+index+'.png'),fullPage:true}).catch(()=>{});console.error('PAGE',index,page.url(),await page.evaluate(()=>document.querySelector('#ajax-notice')?.textContent||''));}process.exitCode=1;}).finally(async()=>{await browser?.close();});
