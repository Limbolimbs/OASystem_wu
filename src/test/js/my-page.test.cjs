// 実サイト・実DBに接続せず、合成応答で本人設定の通信と表示を検証する。
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '../../main/resources/static');
const cdn = 'https://code.jquery.com/jquery-3.7.1.min.js';
let browser, jquery;
before(async () => {
    browser = await chromium.launch({ headless: true, channel: process.env.OA_BROWSER_CHANNEL || 'msedge' });
    const context = await browser.newContext();
    try { const result = await context.request.get(cdn); assert.ok(result.ok()); jquery = await result.body(); }
    finally { await context.close(); }
});
after(async () => browser?.close());
const fixture = `<div id="password-message" hidden tabindex="-1"></div>
<form id="passwordForm" action="/my-page/password" method="post" novalidate data-min-length="12" data-max-length="128">
<label>現在のパスワード<input name="currentPassword" type="password" required></label>
<label>新しいパスワード<input name="newPassword" type="password" required></label>
<label>新しいパスワード（確認）<input name="passwordConfirmation" type="password" required></label>
<button type="submit" disabled>変更</button><button type="reset">クリア</button></form>`;

async function setup(t, handler, pageHtml = fixture) {
    const page = await browser.newPage();
    const errors = [], dialogs = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('dialog', async dialog => { dialogs.push(dialog.type()); await dialog.dismiss(); });
    await page.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url());
        if (request.url() === cdn) return route.fulfill({ contentType: 'text/javascript', body: jquery });
        if (url.pathname === '/test') return route.fulfill({ contentType: 'text/html; charset=utf-8', body:
            `<!doctype html><html lang="ja"><head><meta charset="UTF-8"><meta name="_csrf" content="synthetic-token"><meta name="_csrf_header" content="X-CSRF-TOKEN"></head><body>${pageHtml}</body></html>` });
        const result = await handler(request, url);
        if (result) await route.fulfill(result); else await route.abort();
    });
    await page.goto('https://profile.example.test/test');
    await page.addScriptTag({ url: cdn });
    await page.addScriptTag({ path: path.join(root, 'js/request-security.js') });
    await page.addStyleTag({ path: path.join(root, 'css/message-dialog.css') });
    await page.addStyleTag({ path: path.join(root, 'css/interaction-feedback.css') });
    await page.addScriptTag({ path: path.join(root, 'js/interaction-feedback.js') });
    await page.addScriptTag({ path: path.join(root, 'js/message-dialog.js') });
    await page.addScriptTag({ path: path.join(root, 'js/my-page.js') });
    await page.evaluate(() => new Promise(resolve => $(document).ready(resolve)));
    await page.evaluate(() => { window.sameDocument = true; });
    t.after(async () => { await page.close(); assert.deepEqual(errors, []); assert.deepEqual(dialogs, []); });
    return page;
}

async function fill(page, value = 'NewSecret123!') {
    await page.fill('[name="currentPassword"]', 'old-test');
    await page.fill('[name="newPassword"]', value);
    await page.fill('[name="passwordConfirmation"]', value);
}
const failure = status => ({ status, contentType: 'application/json', body: JSON.stringify({ success: false, message: '操作を確認してください。' }) });

test('パスワードの長さ・種類・確認・現在と同じ値を送信前に拒否する', async t => {
    let posts = 0;
    const page = await setup(t, async () => { posts++; return failure(400); });
    for (const value of ['abc', 'abcdefghijkl', 'old-test']) {
        await fill(page, value);
        await page.click('[type="submit"]');
        await page.waitForSelector('.field-error');
        assert.equal(posts, 0);
    }
    await fill(page); await page.fill('[name="passwordConfirmation"]', 'Mismatch123!');
    await page.click('[type="submit"]');
    assert.match(await page.textContent('body'), /一致しません/); assert.equal(posts, 0);
});

test('独自確認の取消では送信せず、二重送信を防いでCSRF付きAJAXを1回だけ送る', async t => {
    const calls = [];
    const page = await setup(t, async request => {
        calls.push({ type: request.resourceType(), method: request.method(), data: request.postData(), csrf: request.headers()['x-csrf-token'] });
        await new Promise(resolve => setTimeout(resolve, 150)); return failure(400);
    });
    await fill(page); await page.click('[type="submit"]');
    await page.click('.message-dialog-cancel');
    await page.waitForFunction(() => !document.querySelector('dialog').open);
    assert.equal(calls.length, 0);
    assert.equal(await page.evaluate(() => document.activeElement.type), 'submit');
    await page.click('[type="submit"]');
    await page.click('.message-dialog-accept');
    await page.evaluate(() => document.getElementById('passwordForm').requestSubmit());
    await page.waitForFunction(() => !document.querySelector('dialog').open && !document.querySelector('[type="submit"]').disabled && !document.getElementById('password-message').hidden);
    assert.equal(calls.length, 1); assert.equal(calls[0].type, 'xhr'); assert.equal(calls[0].csrf, 'synthetic-token');
    assert.equal(new URLSearchParams(calls[0].data).get('currentPassword'), 'old-test');
    assert.equal(await page.inputValue('[name="currentPassword"]'), '');
    assert.equal(await page.inputValue('[name="newPassword"]'), 'NewSecret123!');
    assert.equal(await page.evaluate(() => window.sameDocument), true);
});

test('CSRF不備では通信しない。403・429・503では再送せず入力と画面を保持する', async t => {
    let posts = 0, status = 403;
    const page = await setup(t, async () => { posts++; return failure(status); });
    await page.evaluate(() => document.querySelector('meta[name="_csrf"]').remove());
    await fill(page); await page.click('[type="submit"]'); await page.click('.message-dialog-accept');
    await page.waitForFunction(() => !document.querySelector('dialog').open && !document.querySelector('[type="submit"]').disabled && !document.getElementById('password-message').hidden);
    assert.equal(posts, 0);
    await page.evaluate(() => { const meta = document.createElement('meta'); meta.name = '_csrf'; meta.content = 'synthetic-token'; document.head.append(meta); });
    for (status of [403, 429, 503]) {
        await fill(page); await page.click('[type="submit"]'); await page.click('.message-dialog-accept');
        await page.waitForFunction(() => !document.querySelector('dialog').open && !document.querySelector('[type="submit"]').disabled && !document.getElementById('password-message').hidden);
        assert.equal(await page.inputValue('[name="currentPassword"]'), '');
        assert.equal(await page.inputValue('[name="newPassword"]'), 'NewSecret123!');
        assert.equal(await page.evaluate(() => window.sameDocument), true);
    }
    assert.equal(posts, 3);
});

test('成功時に秘密入力を消去し、独自完了ウィンドウ後にログインへ移動する', async t => {
    let posts = 0;
    const page = await setup(t, async (request, url) => {
        if (request.method() === 'POST') { posts++; return { contentType: 'application/json', body: JSON.stringify({ success: true, message: '変更しました。', redirectUrl: 'https://malicious.invalid/' }) }; }
        if (url.pathname === '/') return { contentType: 'text/html', body: '<p>合成ログイン画面</p>' };
    });
    await fill(page); await page.click('[type="submit"]'); await page.click('.message-dialog-accept');
    await page.waitForFunction(() => document.querySelector('#message-dialog-title').textContent === 'パスワードを変更しました');
    assert.equal(await page.inputValue('[name="newPassword"]'), '');
    assert.equal(await page.inputValue('[name="currentPassword"]'), '');
    assert.equal(await page.isDisabled('[type="submit"]'), true);
    await page.click('.message-dialog-accept'); await page.waitForURL('https://profile.example.test/');
    assert.equal(posts, 1);
});

test('401では送信を止め秘密入力を消し、ログインリンクを表示する', async t => {
    let posts = 0;
    const page = await setup(t, async () => { posts++; return failure(401); });
    await fill(page); await page.click('[type="submit"]'); await page.click('.message-dialog-accept');
    await page.waitForSelector('#password-message a');
    assert.equal(await page.getAttribute('#password-message a', 'href'), 'https://profile.example.test/');
    assert.equal(await page.inputValue('[name="newPassword"]'), '');
    assert.equal(await page.isDisabled('[type="submit"]'), true); assert.equal(posts, 1);
});

test('入力クリアで独自エラーとカスタム妥当性をリセットする', async t => {
    const page = await setup(t, async () => failure(400));
    await fill(page, 'short'); await page.click('[type="submit"]'); await page.click('[type="reset"]');
    assert.equal(await page.locator('.field-error').count(), 0);
    assert.equal(await page.inputValue('[name="newPassword"]'), '');
    await fill(page); await page.click('[type="submit"]');
    await page.waitForSelector('dialog[open]'); await page.click('.message-dialog-cancel');
});
