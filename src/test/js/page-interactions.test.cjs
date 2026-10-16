// 業務通信・実DBなしのDOM回帰テスト。jQueryのみ公式CDNから読み込む。
const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const fs = require("node:fs");
const { chromium } = require("playwright");
const scripts = path.resolve(__dirname, "../../main/resources/static/js");
const jqueryUrl = "https://code.jquery.com/jquery-3.7.1.min.js";
let jquerySource;
let browser;

before(async () => {
    browser = await chromium.launch({ headless: true, channel: process.env.OA_BROWSER_CHANNEL || "msedge" });
    const context = await browser.newContext();
    try {
        const response = await context.request.get(jqueryUrl, { timeout: 30000 });
        assert.ok(response.ok(), "公式CDNのjQueryを取得できませんでした。");
        jquerySource = await response.body(); // ファイルには保存しない。
    } finally {
        await context.close();
    }
});
after(async () => { await browser?.close(); });

const region = (id, content, query = "") => `<section id="${id}" data-ajax-region
    data-ajax-url="/attendance/manage${query}" data-ajax-page-param="ownPage">${content}</section>`;
const success = { status: 200, contentType: "application/json", body: JSON.stringify({ success: true, message: "操作が完了しました。" }) };
const requestForm = `<form id="request-form" action="/attendance/manage/requests" method="post" data-ajax
    data-ajax-targets="history" data-ajax-reset data-ajax-reset-pages="history">
    <input name="startDate" type="date" value="2026-10-08" required>
    <input name="endDate" type="date" value="2026-10-08" required>
    <textarea name="reason" required></textarea><button type="submit">申請する</button></form>`;

async function setup(t, html, handler, additionalScripts = [], prepare = null) {
    const page = await browser.newPage();
    const errors = [];
    const browserDialogs = [];
    page.on("pageerror", error => errors.push(error.message));
    page.on("dialog", async dialog => { browserDialogs.push(dialog.type()); await dialog.dismiss(); });
    await page.route("**/*", async route => {
        const request = route.request();
        if (request.url() === jqueryUrl) {
            return route.fulfill({ contentType: "text/javascript", body: jquerySource });
        }
        if (request.url() === "https://oa.example.test/test") {
            await route.fulfill({ contentType: "text/html; charset=utf-8", body: `<!DOCTYPE html><html lang="ja"><head><meta charset="UTF-8"><meta name="_csrf" content="test-token"><meta name="_csrf_header" content="X-CSRF-TOKEN"></head><body>${html}</body></html>` });
        } else {
            const response = await handler(request, new URL(request.url()));
            if (response) await route.fulfill(response);
            else await route.abort(); // テスト用応答がないURLは外部へ通信しない。
        }
    });
    await page.goto("https://oa.example.test/test");
    await page.addScriptTag({ url: jqueryUrl });
    await page.addScriptTag({ path: path.join(scripts, "request-security.js") });
    if (prepare) await prepare(page);
    await page.addStyleTag({ path: path.join(scripts, "../css/message-dialog.css") });
    await page.addStyleTag({ path: path.join(scripts, "../css/interaction-feedback.css") });
    await page.addScriptTag({ path: path.join(scripts, "interaction-feedback.js") });
    await page.addScriptTag({ path: path.join(scripts, "message-dialog.js") });
    await page.addScriptTag({ path: path.join(scripts, "page-interactions.js") });
    for (const name of additionalScripts) await page.addScriptTag({ path: path.join(scripts, name) });
    await page.evaluate(() => {
        window.pageSentinel = "same-document";
        document.dispatchEvent(new Event("DOMContentLoaded"));
    });
    await page.evaluate(() => new Promise(resolve => $(document).ready(resolve)));
    t.after(async () => { await page.close(); assert.deepEqual(errors, []); assert.deepEqual(browserDialogs, []); });
    return page;
}

test("申請後は履歴だけを更新し、他フォームの草稿と検索条件を保持する", async t => {
    const calls = [];
    const page = await setup(t, requestForm + region("history", "初期履歴", "?ownPage=2")
        + '<form id="policy"><input name="rule" value="09:00"></form><input id="search" value="田中">',
        async (request, url) => {
            calls.push({ method: request.method(), url: url.href, data: request.postData(), transport: request.resourceType() });
            return request.method() === "POST" ? success : {
                contentType: "text/html", body: region("history", "申請追加済み", "?ownPage=0")
            };
        });
    await page.fill('[name="reason"]', "私用のため");
    await page.fill('[name="rule"]', "10:00");
    await page.click('#request-form button');
    await page.waitForFunction(() => document.getElementById("history").textContent.includes("申請追加済み"));
    assert.equal(await page.inputValue('[name="reason"]'), "");
    assert.equal(await page.inputValue('[name="rule"]'), "10:00");
    assert.equal(await page.inputValue('#search'), "田中");
    assert.equal(calls.filter(call => call.method === "POST").length, 1);
    assert.ok(calls.every(call => call.transport === "xhr"));
    assert.ok(calls[0].data.includes(encodeURIComponent("私用のため")));
    assert.equal(new URL(calls[1].url).searchParams.get("ownPage"), "0");
    assert.equal(await page.evaluate(() => window.pageSentinel), "same-document");
});

test("入力エラーは日本語で表示し、入力内容と一覧を残す", async t => {
    let gets = 0;
    const page = await setup(t, requestForm + region("history", "初期履歴"), async request => {
        if (request.method() !== "POST") gets++;
        return { status: 400, contentType: "application/json", body: JSON.stringify({ success: false, message: "対象期間を確認してください。" }) };
    });
    await page.fill('[name="reason"]', "入力途中の理由");
    await page.click('#request-form button');
    await page.waitForFunction(() => document.getElementById("ajax-notice")?.textContent.includes("対象期間を確認してください。"));
    assert.equal(await page.inputValue('[name="reason"]'), "入力途中の理由");
    assert.equal(await page.textContent('#history'), "初期履歴");
    assert.equal(gets, 0);
    assert.equal(await page.isDisabled('#request-form button'), false);
});

test("二重送信を防ぎ、成功後は送信ボタンを復帰する", async t => {
    let posts = 0;
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    const page = await setup(t, requestForm + region("history", "初期履歴"), async request => {
        if (request.method() === "POST") { posts++; await gate; return success; }
        return { contentType: "text/html", body: region("history", "更新済み") };
    });
    await page.fill('[name="reason"]', "理由");
    await page.evaluate(() => {
        const form = document.getElementById("request-form");
        form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
        form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => document.getElementById("request-form").getAttribute("aria-busy") === "true");
    assert.equal(await page.isDisabled('#request-form button'), true);
    release();
    await page.waitForFunction(() => document.getElementById("ajax-notice")?.textContent.includes("操作が完了"));
    assert.equal(posts, 1);
    assert.equal(await page.isDisabled('#request-form button'), false);
});

test("ページ移動は対象一覧のみ更新し、申請の入力途中の値を保持する", async t => {
    const page = await setup(t, requestForm + region("history", '<a data-ajax-targets="history" href="/attendance/manage?ownPage=1">次へ</a>'),
        async (_request, url) => ({ contentType: "text/html", body: region("history", "2ページ目", `?ownPage=${url.searchParams.get("ownPage")}`) }));
    await page.fill('[name="reason"]', "途中の申請理由");
    await page.click('#history a');
    await page.waitForFunction(() => document.getElementById("history").textContent === "2ページ目");
    assert.equal(await page.inputValue('[name="reason"]'), "途中の申請理由");
    assert.equal(await page.evaluate(() => window.pageSentinel), "same-document");
});

test("承認後も別の行の審査コメントを保持する", async t => {
    const approval = id => `<form action="/attendance/manage/review/${id}/approve" method="post" data-ajax data-ajax-targets="review">
        <input name="comment"><button type="submit">承認${id}</button></form>`;
    const page = await setup(t, region("review", approval(1) + approval(2)), async request => request.method() === "POST"
        ? success : { contentType: "text/html", body: region("review", approval(2)) });
    await page.fill('form[action$="/2/approve"] input', "まだ送信しないコメント");
    await page.click('form[action$="/1/approve"] button');
    await page.waitForFunction(() => !document.querySelector('form[action$="/1/approve"]'));
    assert.equal(await page.inputValue('form[action$="/2/approve"] input'), "まだ送信しないコメント");
});

test("保存成功後に表示取得が失敗しても、再取得ではPOSTを再送しない", async t => {
    let posts = 0;
    let gets = 0;
    const page = await setup(t, requestForm + region("history", "初期履歴"), async request => {
        if (request.method() === "POST") { posts++; return success; }
        gets++;
        return gets === 1 ? { status: 503, contentType: "text/plain", body: "unavailable" }
            : { contentType: "text/html", body: region("history", "最新表示") };
    });
    await page.fill('[name="reason"]', "理由");
    await page.click('#request-form button');
    await page.waitForFunction(() => document.getElementById("ajax-notice")?.textContent.includes("再送信は不要"));
    await page.getByRole("button", { name: "表示を再取得" }).click();
    await page.waitForFunction(() => document.getElementById("history").textContent === "最新表示");
    assert.equal(posts, 1);
    assert.equal(gets, 2);
});

test("検索はGETの部分取得で実行し、無関係の草稿を残す", async t => {
    let received;
    const html = '<form id="search-form" action="/attendance/history" method="get" data-ajax data-ajax-targets="history-results">'
        + '<input name="from" type="date" value="2026-10-01"><input name="to" type="date" value="2026-10-08">'
        + '<button>検索</button></form>' + region("history-results", "初期結果") + requestForm;
    const page = await setup(t, html, async (request, url) => {
        received = { method: request.method(), from: url.searchParams.get("from"), fragment: url.searchParams.get("fragment") };
        return { contentType: "text/html", body: region("history-results", "検索結果") };
    });
    await page.fill('[name="reason"]', "他の申請の草稿");
    await page.click('#search-form button');
    await page.waitForFunction(() => document.getElementById("history-results").textContent === "検索結果");
    assert.deepEqual(received, { method: "GET", from: "2026-10-01", fragment: "history-results" });
    assert.equal(await page.inputValue('[name="reason"]'), "他の申請の草稿");
});

test("社員一覧の検索は部分更新後の新しい行にも適用する", async t => {
    const row = (name, role) => `<div class="employee-row" data-username="${name}" data-realname="${name}" data-role="${role}">
        <span class="role-badge">${role}</span></div>`;
    const html = '<p id="currentTime"></p><span id="lastUpdated"></span><span id="visibleCount">2</span><input id="employeeSearch">'
        + `<div id="employee-data" data-ajax-region data-ajax-url="/users">${row("田中", "一般社員")}${row("伊藤", "総務担当")}`
        + '<div id="noSearchResult"></div><form action="/users/2/role" method="post" data-ajax data-ajax-targets="employee-data"><button>更新</button></form></div>';
    const page = await setup(t, html, async request => request.method() === "POST" ? success
        : { contentType: "text/html", body: `<div id="employee-data" data-ajax-region data-ajax-url="/users">${row("田中", "総務担当")}${row("伊藤", "総務担当")}<div id="noSearchResult"></div></div>` }, ["users.js"]);
    await page.fill('#employeeSearch', "総務担当");
    assert.equal(await page.textContent('#visibleCount'), "1");
    await page.click('#employee-data button');
    await page.waitForFunction(() => document.getElementById("visibleCount").textContent === "2");
    assert.equal(await page.inputValue('#employeeSearch'), "総務担当");
});

test("申請種類を成功後にリセットしたとき、項目のdisabled状態も一致する", async t => {
    const html = '<p id="currentTime"></p><form id="request-form" action="/attendance/manage/requests" method="post" data-ajax data-ajax-reset>'
        + '<select name="type" id="requestType"><option value="CORRECTION">打刻修正</option><option value="OVERTIME">残業</option></select>'
        + '<label class="correction-field"><input name="clockInAt" type="datetime-local"></label>'
        + '<label class="overtime-field"><input name="overtimeEndAt" type="datetime-local"></label><button>申請する</button></form>';
    const page = await setup(t, html, async () => success, ["attendance-manage.js"]);
    await page.selectOption('#requestType', "OVERTIME");
    await page.fill('[name="overtimeEndAt"]', "2026-10-08T20:00");
    await page.click('#request-form button');
    await page.waitForFunction(() => !document.getElementById("request-form").hasAttribute("aria-busy"));
    assert.equal(await page.inputValue('#requestType'), "CORRECTION");
    assert.equal(await page.isDisabled('[name="clockInAt"]'), false);
    assert.equal(await page.isDisabled('[name="overtimeEndAt"]'), true);
});

test("同じ申請への承認と差し戻しを同時に送信しない", async t => {
    let posts = 0;
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    const html = '<div data-ajax-lock="review:1">'
        + '<form id="approve" action="/review/1/approve" method="post" data-ajax><button>承認</button></form>'
        + '<form id="reject" action="/review/1/reject" method="post" data-ajax><button>差し戻し</button></form></div>';
    const page = await setup(t, html, async () => { posts++; await gate; return success; });
    await page.evaluate(() => {
        for (const id of ["approve", "reject"]) document.getElementById(id)
            .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    });
    release();
    await page.waitForFunction(() => document.getElementById("ajax-notice"));
    assert.equal(posts, 1);
});

test("勤務ルールの同名チェックボックスをすべて送信する", async t => {
    let values;
    const html = '<form action="/attendance/manage/policy" method="post" data-ajax>'
        + '<input name="workDay" type="checkbox" value="1" checked><input name="workDay" type="checkbox" value="2" checked>'
        + '<input name="workDay" type="checkbox" value="4"><button>保存</button></form>';
    const page = await setup(t, html, async request => {
        values = new URLSearchParams(request.postData()).getAll("workDay");
        return success;
    });
    await page.click('button');
    await page.waitForFunction(() => document.getElementById("ajax-notice"));
    assert.deepEqual(values, ["1", "2"]);
});

test("無効化の確認でキャンセルした場合は送信しない", async t => {
    let requests = 0;
    const page = await setup(t, '<form action="/users/2/deactivate" method="post" data-ajax data-confirm-message="無効化しますか？"><button>無効化</button></form>',
        async () => { requests++; return success; });
    await page.click('button');
    await page.click('.message-dialog-cancel');
    await page.waitForFunction(() => !document.getElementById("portal-message-dialog").open);
    assert.equal(requests, 0);
    assert.equal(await page.isDisabled('button'), false);
});

test("独自確認は安全な文字表示・Escape取消・フォーカス復帰に対応する", async t => {
    let posts = 0;
    const page = await setup(t, '<form action="/users/2/deactivate" method="post" data-ajax data-confirm-message="&lt;script&gt;危険&lt;/script&gt;"><button id="disable">無効化</button></form>',
        async () => { posts++; return success; });
    await page.click('#disable');
    assert.equal(await page.textContent('#message-dialog-body'), '<script>危険</script>');
    assert.equal(await page.locator('#message-dialog-body script').count(), 0);
    assert.equal(await page.evaluate(() => document.activeElement.className), 'message-dialog-cancel');
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.getElementById('portal-message-dialog').open);
    assert.equal(await page.evaluate(() => document.activeElement.id), 'disable');
    assert.equal(posts, 0);
});

test("確認中に連打しても独自ウィンドウとPOSTは各1回、狭幅でも収まる", async t => {
    let posts = 0;
    const page = await setup(t, '<form id="protected" action="/users/2/deactivate" method="post" data-ajax data-confirm-message="このユーザーを無効化しますか？"><button>無効化</button></form>',
        async () => { posts++; return success; });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.evaluate(() => {
        const form = document.getElementById('protected');
        for (let i = 0; i < 3; i++) form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => document.getElementById('portal-message-dialog').open);
    assert.equal(await page.locator('#portal-message-dialog').count(), 1);
    const box = await page.locator('#portal-message-dialog').boundingBox();
    assert.ok(box.x >= 0 && box.x + box.width <= 390);
    const preview = path.resolve(__dirname, '../../../target/ui-preview/message-dialog-mobile.png');
    fs.mkdirSync(path.dirname(preview), { recursive: true });
    await page.screenshot({ path: preview });
    await page.click('.message-dialog-accept');
    await page.waitForFunction(() => document.getElementById('ajax-notice')?.textContent.includes('操作が完了'));
    assert.equal(posts, 1);
});

const registrationHtml = `<p id="currentTime"></p><form id="registration" action="/users/save" method="post" data-ajax data-ajax-reset novalidate>
    <label for="password">初期パスワード</label><input type="password" id="password" name="password" required>
    <button id="passwordToggle" type="button">表示</button><label for="passwordConfirmation">確認用パスワード</label>
    <input type="password" id="passwordConfirmation" name="passwordConfirmation" required><button id="register">従業員を登録</button></form>`;

test("登録の長さ・文字種類・確認一致を日本語で表示し、弱い入力では通信しない", async t => {
    let posts = 0;
    const page = await setup(t, registrationHtml, async () => { posts++; return success; }, ['user-form.js']);
    for (const value of ['Aa123456789', 'abcdefgh1234', 'Aa1' + 'x'.repeat(126)]) {
        await page.fill('#password', value); await page.fill('#passwordConfirmation', value);
        await page.click('#register');
        assert.ok(await page.locator('.field-error').count() > 0);
        assert.equal(posts, 0);
    }
    await page.fill('#password', 'Abcdefgh1234'); await page.fill('#passwordConfirmation', 'Different123!');
    await page.click('#register');
    assert.match(await page.textContent('.field-error'), /一致しません/);
    assert.equal(posts, 0);
    await page.fill('#passwordConfirmation', 'Abcdefgh1234'); await page.click('#register');
    await page.waitForFunction(() => document.getElementById('ajax-notice')?.textContent.includes('操作が完了'));
    assert.equal(posts, 1);
    assert.equal(await page.inputValue('#password'), '');
    assert.equal(await page.locator('.field-error').count(), 0);
});

test("入力不備は独自の欄内メッセージを使い、HTML標準の警告を出さない", async t => {
    let posts = 0;
    const page = await setup(t, '<form action="/test-save" method="post" data-ajax><label for="reason">申請理由</label><input id="reason" required><button>保存</button></form>',
        async () => { posts++; return success; });
    await page.click('button');
    assert.equal(await page.evaluate(() => document.querySelector('form').noValidate), true);
    assert.equal(await page.textContent('.field-error'), '申請理由を入力してください。');
    assert.equal(await page.getAttribute('#reason', 'aria-invalid'), 'true');
    assert.equal(posts, 0);
});

test("本番のJS・HTMLにブラウザーのalert/confirm/promptとreportValidityを残さない", () => {
    const nativeDialogs = /\bwindow\s*\.\s*(?:alert|confirm|prompt)\s*\(|(?<![\w.])(?:alert|confirm|prompt)\s*\(|\.reportValidity\s*\(/;
    const templates = path.resolve(scripts, '../../templates');
    for (const file of fs.readdirSync(scripts).filter(name => name.endsWith('.js'))) {
        assert.doesNotMatch(fs.readFileSync(path.join(scripts, file), 'utf8'), nativeDialogs);
    }
    for (const file of fs.readdirSync(templates).filter(name => name.endsWith('.html'))) {
        const source = fs.readFileSync(path.join(templates, file), 'utf8');
        assert.doesNotMatch(source, nativeDialogs);
        assert.match(source, /\/js\/message-dialog\.js/);
        assert.match(source, /\/css\/message-dialog\.css/);
    }
});

test("ログインHTMLなどの予期しない応答で既存一覧を置き換えない", async t => {
    const page = await setup(t, region("history", '<a data-ajax-targets="history" href="/attendance/manage?ownPage=1">次へ</a>'),
        async () => ({ contentType: "text/html", body: '<html><body><form>ログイン</form></body></html>' }));
    await page.click('#history a');
    await page.waitForFunction(() => document.getElementById("ajax-notice")?.textContent.includes("形式が正しくありません"));
    assert.equal(await page.textContent('#history a'), "次へ");
    assert.equal(await page.evaluate(() => window.pageSentinel), "same-document");
});

test("セッション切れの場合のみログイン画面へ移動する", async t => {
    const page = await setup(t, '<form action="/users/2/activate" method="post" data-ajax><button>有効化</button></form>',
        async (request, url) => url.pathname === "/"
            ? { contentType: "text/html; charset=utf-8", body: '<p>ログイン</p>' }
            : { status: 401, contentType: "application/json", body: JSON.stringify({ success: false, message: "再ログインしてください。", redirectUrl: "/" }) });
    await page.click('button');
    await page.waitForURL("https://oa.example.test/");
});

const loginHtml = '<input id="userName" name="userName"><input id="password" name="password" type="password">'
    + '<div class="error-message" hidden></div><button class="login-button" type="button">ログイン</button>';

test("ログイン429はRetry-Afterを日本語で案内しPOSTを自動再送しない", async t => {
    let posts = 0;
    const page = await setup(t, loginHtml, async () => {
        posts++;
        return {status:429,contentType:'application/json',headers:{'Retry-After':'900'},body:JSON.stringify({success:false,code:'LOGIN_LIMITED',message:'ログイン試行が制限されています。'})};
    }, ['login.js']);
    await page.fill('#userName','EMP0001'); await page.fill('#password','wrong'); await page.click('.login-button');
    await page.waitForFunction(() => !document.querySelector('.error-message').hidden);
    assert.match(await page.textContent('.error-message'), /制限.*15分/);
    assert.equal(posts,1); assert.equal(await page.isDisabled('.login-button'),false);
    assert.equal(await page.inputValue('#userName'),'EMP0001');
});

test("会話の操作通知は実操作時だけ送信し、合成イベントとタイマーで延長しない", async t => {
    const calls = [];
    const page = await setup(t, '<meta name="security_activity_url" content="/session/activity"><input id="activity"><button id="touch" type="button">操作</button>', async request => {
        calls.push({path:new URL(request.url()).pathname,method:request.method(),token:request.headers()['x-csrf-token']});
        return success;
    });
    await page.evaluate(() => {
        window.originalTime = Date.now; window.clockShift=60001; Date.now=()=>window.originalTime()+window.clockShift;
        document.dispatchEvent(new Event('pointerdown')); document.dispatchEvent(new Event('input'));
    });
    assert.equal(calls.length,0);
    await page.click('#touch'); await page.waitForFunction(() => $.active===0);
    assert.deepEqual(calls,[{path:'/session/activity',method:'POST',token:'test-token'}]);
    await page.click('#touch'); assert.equal(calls.length,1);
    await page.evaluate(() => {window.clockShift+=60001;});
    await page.fill('#activity','実操作'); await page.waitForFunction(() => $.active===0);
    assert.equal(calls.length,2);
});

test("ログイン失敗は$.ajaxで処理し、入力を残して画面遷移しない", async t => {
    let posted;
    const page = await setup(t, loginHtml, async request => {
        posted = { type: request.resourceType(), values: Object.fromEntries(new URLSearchParams(request.postData())) };
        return { contentType: "text/plain", body: "NG" };
    }, ["login.js"]);
    await page.fill('#userName', " tanaka ");
    await page.fill('#password', "wrong-password");
    await page.click('.login-button');
    await page.waitForFunction(() => !document.querySelector(".error-message").hidden);
    assert.equal(posted.type, "xhr");
    assert.deepEqual(posted.values, { userName: "tanaka", password: "wrong-password" });
    assert.match(await page.textContent('.error-message'), /ログインIDまたはパスワードが正しくありません/);
    assert.equal(await page.inputValue('#password'), "wrong-password");
    assert.equal(await page.evaluate(() => window.pageSentinel), "same-document");
});

test("ログイン成功時だけホームへ移動する", async t => {
    let posts = 0;
    const page = await setup(t, loginHtml, async (request, url) => {
        if (url.pathname === "/home") return { contentType: "text/html", body: '<p>ホーム</p>' };
        posts++;
        assert.equal(request.method(), "POST");
        return { contentType: "text/plain", body: "OK" };
    }, ["login.js"]);
    await page.fill('#userName', "tanaka");
    await page.fill('#password', "test-password");
    await page.click('.login-button');
    await page.waitForURL("https://oa.example.test/home");
    assert.equal(posts, 1);
});

test("未入力のログインは通信せず日本語メッセージを表示する", async t => {
    let requests = 0;
    const page = await setup(t, loginHtml, async () => { requests++; return success; }, ["login.js"]);
    await page.click('.login-button');
    assert.match(await page.textContent('.error-message'), /ログインIDとパスワードを入力してください/);
    assert.equal(requests, 0);
});

test("ログインの通信エラー後はボタンを再び使用できる", async t => {
    const page = await setup(t, loginHtml, async () => ({ status: 503, contentType: "text/plain", body: "unavailable" }), ["login.js"]);
    await page.fill('#userName', "tanaka");
    await page.fill('#password', "test-password");
    await page.click('.login-button');
    await page.waitForFunction(() => !document.querySelector(".error-message").hidden);
    assert.match(await page.textContent('.error-message'), /通信エラーが発生しました/);
    assert.equal(await page.isDisabled('.login-button'), false);
    assert.equal(await page.inputValue('#password'), "test-password");
});

test("ログイン送信中のEnter連打でもPOSTは一度だけ送る", async t => {
    let posts = 0;
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    const page = await setup(t, loginHtml, async () => {
        posts++; await gate; return { contentType: "text/plain", body: "NG" };
    }, ["login.js"]);
    await page.fill('#userName', "tanaka");
    await page.fill('#password', "test-password");
    await page.evaluate(() => {
        const field = document.getElementById("password");
        field.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
        field.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
    });
    assert.equal(await page.isDisabled('.login-button'), true);
    release();
    await page.waitForFunction(() => !document.querySelector(".error-message").hidden);
    assert.equal(posts, 1);
});

const dashboardHtml = '<span id="today"></span><div id="errorBox" style="display:none"></div>'
    + '<span id="totalSales"></span><span id="totalOrders"></span><span id="totalCustomers"></span>'
    + '<span id="employeeCount"></span><span id="salesGrowth"></span>'
    + '<div id="salesChart"></div><div id="departmentChart"></div><div id="activityChart"></div>'
    + '<table><tbody id="monthlyTableBody"></tbody></table>';
const dashboardData = {
    summary: { totalSales: 1000000, totalOrders: 25, totalCustomers: 5, employeeCount: 3, salesGrowth: 2.5 },
    months: ["2026-10"], monthlySales: [1000000], monthlyOrders: [25], monthlyCustomers: [5],
    departmentSales: [{ name: "営業部", value: 1000000 }]
};

test("ホームの$.ajaxが取得した値をカード・表・3つのグラフへ反映する", async t => {
    let transport;
    const page = await setup(t, dashboardHtml, async request => {
        transport = request.resourceType();
        assert.equal(request.method(), "GET");
        return { contentType: "application/json", body: JSON.stringify(dashboardData) };
    }, ["home.js"], async page => page.evaluate(() => {
        window.chartOptions = {};
        window.echarts = { init: element => ({
            setOption: option => { window.chartOptions[element.id] = option; }, resize: () => {}
        }) };
    }));
    await page.waitForFunction(() => window.chartOptions.activityChart);
    assert.equal(transport, "xhr");
    assert.equal(await page.textContent('#totalOrders'), "25 件");
    assert.equal(await page.textContent('#employeeCount'), "3 人");
    assert.equal(await page.textContent('#salesGrowth'), "+2.5%");
    assert.equal(await page.locator('#monthlyTableBody tr').count(), 1);
    assert.deepEqual(await page.evaluate(() => window.chartOptions.salesChart.series[0].data), [1]);
    assert.equal(await page.evaluate(() => window.pageSentinel), "same-document");
});

test("EChartsがなくても取得した一覧は表示し、専用のエラーを出す", async t => {
    const page = await setup(t, dashboardHtml, async () => ({ contentType: "application/json", body: JSON.stringify(dashboardData) }), ["home.js"]);
    await page.waitForFunction(() => document.getElementById("errorBox").textContent.includes("ECharts"));
    assert.equal(await page.locator('#monthlyTableBody tr').count(), 1);
});

test("ホームの権限エラーはその場で表示する", async t => {
    const page = await setup(t, dashboardHtml, async () => ({ status: 403, contentType: "application/json", body: "{}" }), ["home.js"]);
    await page.waitForFunction(() => document.getElementById("errorBox").textContent.includes("権限がありません"));
    assert.equal(await page.evaluate(() => window.pageSentinel), "same-document");
});

test("通信の実装は$.ajaxに統一し、各画面で先にCDNのjQueryを読む", () => {
    for (const file of ["page-interactions.js", "login.js", "home.js", "sales.js"]) {
        const source = fs.readFileSync(path.join(scripts, file), "utf8");
        assert.doesNotMatch(source, /\bfetch\s*\(/);
        assert.match(source, /\$\.ajax\s*\(/);
    }
    const templates = path.resolve(scripts, "../../templates");
    for (const [name, script] of [["log-in", "login"], ["home", "home"], ["attendance", "page-interactions"],
        ["attendance-history", "page-interactions"], ["attendance-manage", "page-interactions"], ["users", "page-interactions"], ["user-form", "page-interactions"],
        ["sales", "sales"], ["departments", "page-interactions"], ["notices", "page-interactions"]]) {
        const source = fs.readFileSync(path.join(templates, name + ".html"), "utf8");
        const jquery = source.indexOf(jqueryUrl);
        assert.ok(jquery >= 0 && jquery < source.indexOf(`/js/${script}.js`), name);
        assert.doesNotMatch(source, /\/js\/vendor\/jquery/);
        assert.doesNotMatch(source, /\/(?:js|css)\/ajax\./);
        if (!["log-in", "home"].includes(name)) assert.match(source, /\/css\/status-messages\.css/);
    }
});

const salesHtml = `<section id="sales-entry"><h2 id="salesFormTitle">月次実績を登録</h2>
    <form id="salesForm" action="/sales/save" method="post" data-ajax data-ajax-targets="sales-data">
    <input type="hidden" name="id"><input type="hidden" name="version">
    <input id="salesMonth" name="salesMonth" type="month" value="2026-10" required>
    <select id="salesDepartment" name="department" required><option value="">部署を選択</option><option value="営業部">営業部</option></select>
    <input type="number" name="salesAmount" min="0" step="0.01" required><input type="number" name="orderCount" min="0" required>
    <input type="number" name="customerCount" min="0" required><textarea name="notes"></textarea>
    <input type="checkbox" name="confirmed" value="true" required><button type="submit">実績を保存</button>
    <button id="salesReset" type="button">新規入力に戻る</button></form></section>
    <section id="sales-data" data-ajax-region data-ajax-url="/sales"><button class="sales-edit" data-id="1" type="button">編集</button></section>`;
const salesDetails = { id: 1, version: 3, salesMonth: "2026-09", department: "営業部",
    salesAmount: 18500000, orderCount: 920, customerCount: 95, notes: "照合済み" };

test("営業実績の保存は$.ajaxで一覧だけを更新し、入力を新規状態に戻す", async t => {
    const calls = [];
    const page = await setup(t, salesHtml, async (request, url) => {
        calls.push({ method: request.method(), data: request.postData(), transport: request.resourceType(), path: url.pathname });
        return request.method() === "POST" ? success : { contentType: "text/html", body: '<section id="sales-data" data-ajax-region data-ajax-url="/sales">営業実績保存済み</section>' };
    }, ["sales.js"]);
    await page.selectOption('#salesDepartment', "営業部"); await page.fill('[name="salesAmount"]', "1200000.25");
    await page.fill('[name="orderCount"]', "20"); await page.fill('[name="customerCount"]', "10");
    await page.check('[name="confirmed"]'); await page.click('#salesForm button[type="submit"]');
    await page.waitForFunction(() => document.getElementById("sales-data").textContent.includes("保存済み"));
    await page.waitForFunction(() => !document.querySelector('#salesForm [name="confirmed"]').checked);
    assert.equal(await page.inputValue('[name="salesAmount"]'), "");
    assert.equal(await page.inputValue('[name="id"]'), "");
    assert.ok(calls.every(call => call.transport === "xhr"));
    const data = new URLSearchParams(calls[0].data);
    assert.equal(data.get("department"), "営業部"); assert.equal(data.get("salesAmount"), "1200000.25");
    assert.equal(data.get("confirmed"), "true"); assert.equal(await page.evaluate(() => window.pageSentinel), "same-document");
});

test("編集実績は版数と固定部署を送信し、成功後の部署選択も復帰する", async t => {
    let posted;
    const page = await setup(t, salesHtml, async (request, url) => {
        if (url.pathname === "/sales/1") return { contentType: "application/json", body: JSON.stringify(salesDetails) };
        if (request.method() === "POST") { posted = new URLSearchParams(request.postData()); return success; }
        return { contentType: "text/html", body: '<section id="sales-data" data-ajax-region data-ajax-url="/sales">更新済み</section>' };
    }, ["sales.js"]);
    await page.click('.sales-edit'); await page.click('.message-dialog-accept');
    await page.waitForFunction(() => document.querySelector('#salesForm [name="id"]').value === "1");
    assert.equal(await page.inputValue('[name="version"]'), "3");
    assert.equal(await page.isDisabled('#salesDepartment'), true);
    assert.equal(await page.getAttribute('#salesMonth', "readonly"), "");
    await page.fill('[name="salesAmount"]', "20000000"); await page.check('[name="confirmed"]');
    await page.click('#salesForm button[type="submit"]');
    await page.waitForFunction(() => document.getElementById("salesFormTitle").textContent === "月次実績を登録");
    assert.equal(posted.get("id"), "1"); assert.equal(posted.get("version"), "3");
    assert.deepEqual(posted.getAll("department"), ["営業部"]);
    assert.equal(await page.isDisabled('#salesDepartment'), false);
    assert.equal(await page.locator('[data-edit-department]').count(), 0);
});

test("営業実績の競合時は編集入力を保持し、勝手に上書き再送しない", async t => {
    let posts = 0;
    const page = await setup(t, salesHtml, async (request, url) => {
        if (url.pathname === "/sales/1") return { contentType: "application/json", body: JSON.stringify(salesDetails) };
        posts++;
        return { status: 409, contentType: "application/json", body: JSON.stringify({ success: false, message: "他の利用者が実績を更新しています。" }) };
    }, ["sales.js"]);
    await page.click('.sales-edit'); await page.click('.message-dialog-accept');
    await page.waitForFunction(() => document.querySelector('#salesForm [name="id"]').value === "1");
    await page.fill('[name="salesAmount"]', "22000000"); await page.check('[name="confirmed"]');
    await page.click('#salesForm button[type="submit"]');
    await page.waitForFunction(() => document.getElementById("ajax-notice")?.textContent.includes("更新しています"));
    assert.equal(await page.inputValue('[name="salesAmount"]'), "22000000");
    assert.equal(await page.inputValue('[name="version"]'), "3"); assert.equal(posts, 1);
    await page.click('#salesReset'); assert.equal(await page.isDisabled('#salesDepartment'), false);
});

test("ホームの再取得は同じ3つのグラフを更新し、再初期化やページ遷移をしない", async t => {
    let reads = 0;
    const page = await setup(t, dashboardHtml + '<button id="dashboardRefresh">再取得</button><span id="dashboardStatus"></span>',
        async () => {
            reads++;
            const value = reads * 1000000;
            const data = { ...dashboardData, summary: { ...dashboardData.summary, totalSales: value }, monthlySales: [value] };
            return { contentType: "application/json", body: JSON.stringify(data) };
        }, ["home.js"], async page => page.evaluate(() => {
            window.chartInitializations = 0; window.chartOptions = {};
            window.echarts = { init: element => {
                window.chartInitializations++;
                return { setOption: option => { window.chartOptions[element.id] = option; }, resize: () => {} };
            } };
        }));
    await page.waitForFunction(() => window.chartOptions.activityChart);
    await page.click('#dashboardRefresh');
    await page.waitForFunction(() => window.chartOptions.salesChart.series[0].data[0] === 2);
    assert.equal(await page.evaluate(() => window.chartInitializations), 3);
    assert.equal(reads, 2); assert.equal(await page.evaluate(() => window.pageSentinel), "same-document");
    assert.ok((await page.textContent('#totalSales')).includes("2,000,000"));
});

for (const name of ["sales", "departments"]) {
    const preview = path.resolve(__dirname, "../../../target/ui-preview", name + ".html");
    test(`${name}の実Thymeleaf生成画面をモック応答で表示・撮影する`, { skip: !fs.existsSync(preview) }, async t => {
        const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } });
        t.after(() => page.close());
        const errors = []; page.on("pageerror", error => errors.push(error.message));
        await page.route("**/*", async route => {
            if (route.request().url() === jqueryUrl) {
                return route.fulfill({ contentType: "text/javascript", body: jquerySource });
            }
            const url = new URL(route.request().url());
            if (url.pathname === "/test") return route.fulfill({ contentType: "text/html; charset=utf-8", body: fs.readFileSync(preview, "utf8") });
            if (/^\/(css|js)\/[a-zA-Z0-9./_-]+$/.test(url.pathname) && !url.pathname.includes("..")) {
                const file = path.join(scripts, "..", url.pathname.slice(1));
                return route.fulfill({ contentType: url.pathname.endsWith(".css") ? "text/css" : "text/javascript", body: fs.readFileSync(file) });
            }
            return route.abort();
        });
        await page.goto("https://oa.example.test/test");
        await page.waitForFunction(() => document.getElementById("currentTime").textContent !== "--");
        assert.equal(await page.locator('aside.sidebar').count(), 1);
        assert.equal(await page.locator('.nav-item.active').count(), 1);
        await page.screenshot({ path: preview.replace(/\.html$/, ".png"), fullPage: true });
        if (name === "sales" && await page.locator('.sales-edit').count()) {
            await page.locator('.sales-edit').first().click();
            await page.waitForFunction(() => document.getElementById('portal-message-dialog')?.open);
            await page.screenshot({ path: preview.replace(/\.html$/, '-confirmation.png') });
            await page.click('.message-dialog-cancel');
        }
        assert.deepEqual(errors, []);
    });
}

test("登録の実画面に日本語ルールと独自の欄内エラーを表示する", async t => {
    const preview = path.resolve(__dirname, '../../../target/ui-preview/user-form.html');
    if (!fs.existsSync(preview)) { t.skip('MVC画面プレビューなし'); return; }
    const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } });
    t.after(() => page.close());
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    let posts = 0;
    await page.route('**/*', async route => {
        const request = route.request();
        if (request.url() === jqueryUrl) return route.fulfill({ contentType: 'text/javascript', body: jquerySource });
        const url = new URL(request.url());
        if (url.pathname === '/test') return route.fulfill({ contentType: 'text/html; charset=utf-8', body: fs.readFileSync(preview, 'utf8') });
        if (/^\/(css|js)\/[a-zA-Z0-9./_-]+$/.test(url.pathname) && !url.pathname.includes('..')) {
            return route.fulfill({ contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript', body: fs.readFileSync(path.join(scripts, '..', url.pathname.slice(1))) });
        }
        if (request.method() === 'POST') posts++;
        return route.abort();
    });
    await page.goto('https://oa.example.test/test');
    await page.waitForFunction(() => document.getElementById('currentTime').textContent !== '--');
    assert.match(await page.textContent('#passwordRequirements'), /12～128文字/);
    await page.fill('#username', 'EMP000123'); await page.fill('#password', 'abc');
    await page.fill('#passwordConfirmation', 'abc'); await page.fill('#realName', '田中 太郎');
    await page.click('form[data-ajax] button[type="submit"]');
    await page.waitForFunction(() => document.querySelector('.field-error')?.textContent.includes('12～128'));
    assert.equal(posts, 0);
    await page.screenshot({ path: preview.replace('.html', '-validation.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
    await page.screenshot({ path: preview.replace('.html', '-validation-mobile.png'), fullPage: true });
    assert.deepEqual(errors, []);
});

test("全体CSRFは同一オリジンのPOSTだけに付加し、外部へ漏らさない", async t => {
    const calls = [];
    const page = await setup(t, '', async request => {
        calls.push({ url: request.url(), method: request.method(), token: request.headers()['x-csrf-token'] });
        return success;
    });
    await page.evaluate(async () => {
        for (const [url, type] of [['/save', 'POST'], ['/read', 'GET'], ['https://other.example.test/save', 'POST']]) {
            await new Promise(resolve => $.ajax({ url, type, dataType: 'json', complete: resolve }));
        }
    });
    assert.equal(calls.length, 3);
    assert.equal(calls[0].token, 'test-token');
    assert.equal(calls[1].token, undefined);
    assert.equal(calls[2].token, undefined);
});

test("CSRF読み込み不備では送信せずボタンを復帰、失効403でも草稿を保持", async t => {
    let posts = 0;
    const page = await setup(t, requestForm + region('history', '元の履歴'), async request => {
        if (request.method() === 'POST') posts++;
        return { status: 403, contentType: 'application/json', body: JSON.stringify({success:false,code:'CSRF_INVALID',message:'画面を再表示してください。'}) };
    });
    await page.fill('[name="reason"]', '保持する草稿');
    await page.evaluate(() => document.querySelector('meta[name="_csrf"]').remove());
    await page.click('#request-form button');
    await page.waitForFunction(() => !document.querySelector('#request-form button').disabled);
    assert.equal(posts, 0);
    await page.evaluate(() => { const token = document.createElement('meta'); token.name='_csrf'; token.content='expired'; document.head.append(token); });
    await page.click('#request-form button');
    await page.waitForFunction(() => document.getElementById('ajax-notice')?.textContent.includes('再表示'));
    assert.equal(posts, 1);
    assert.equal(await page.inputValue('[name="reason"]'), '保持する草稿');
    assert.equal(await page.textContent('#history'), '元の履歴');
});

test("ログアウトは共通CSRF付きPOST、GETリンクでは実行しない", async t => {
    let logoutRequest;
    const page = await setup(t, '<form action="/logout" method="post" data-logout><button>ログアウト</button></form>', async request => {
        if (request.method() === 'POST') { logoutRequest = { method: request.method(), token: request.headers()['x-csrf-token'] }; return {contentType:'application/json',body:JSON.stringify({success:true,redirectUrl:'/'})}; }
        return {contentType:'text/html',body:'<html lang="ja"><body>ログイン</body></html>'};
    });
    await page.click('[data-logout] button');
    await page.waitForURL('https://oa.example.test/');
    assert.deepEqual(logoutRequest, {method:'POST',token:'test-token'});
});
