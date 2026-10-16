// ブラウザ・DBに接続しない、ナビゲーション状態の単体テスト。
const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const source = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/js/portal-navigation.js"), "utf8");

function fixture(mobile = true) {
    let active;
    let dialogOpen = false;
    const events = new Map();
    const classes = () => {
        const values = new Set();
        return { add: name => values.add(name), contains: name => values.has(name),
            toggle(name, enabled) { if (enabled) values.add(name); else values.delete(name); } };
    };
    function element(parent = null) {
        const handlers = new Map();
        return { parent, hidden: false, inert: false, attributes: {},
            addEventListener: (name, fn) => handlers.set(name, fn),
            dispatch(name, event = {}) { handlers.get(name)?.(event); },
            setAttribute(name, value) { this.attributes[name] = value; },
            getClientRects: () => [{}],
            focus() { for (let p = this; p; p = p.parent) if (p.inert) throw new Error("inert内へのフォーカス"); active = this; },
            contains(target) { for (let p = target; p; p = p.parent) if (p === this) return true; return false; }
        };
    }
    const sidebar = element();
    const main = element();
    const toggle = element(main);
    const close = element(sidebar);
    const link = element(sidebar);
    const logout = element(sidebar);
    const backdrop = element(); backdrop.hidden = true;
    sidebar.querySelectorAll = () => [close, link, logout];
    const media = { matches: mobile, addEventListener: (_, fn) => { media.change = fn; } };
    const document = { body: { classList: classes() }, documentElement: { classList: classes() },
        get activeElement() { return active; },
        getElementById: () => sidebar,
        querySelector: key => ({ "[data-navigation-toggle]": toggle, "[data-navigation-close]": close,
            "[data-navigation-backdrop]": backdrop, ".main-area": main, "dialog[open]": dialogOpen ? {} : null })[key],
        addEventListener: (name, fn) => events.set(name, fn) };
    vm.runInNewContext(source, { document, window: { matchMedia: () => media } });
    return { document, sidebar, main, toggle, close, link, logout, backdrop, media,
        setDialog(value) { dialogOpen = value; },
        key(key, shiftKey = false) { const e = { key, shiftKey, prevented: false, preventDefault() { this.prevented = true; } }; events.get("keydown")?.(e); return e; } };
}

test("狭幅の初期状態はメニューだけを非活性にし、本文は利用できる", () => {
    const f = fixture();
    assert.equal(f.sidebar.inert, true);
    assert.equal(f.main.inert, false);
    assert.equal(f.backdrop.hidden, true);
    assert.equal(f.toggle.attributes["aria-expanded"], "false");
});

test("開閉とEscapeでフォーカスを復帰し、本文のinertを解除する", () => {
    const f = fixture();
    f.toggle.focus(); f.toggle.dispatch("click");
    assert.equal(f.document.activeElement, f.close);
    assert.equal(f.main.inert, true);
    assert.equal(f.sidebar.inert, false);
    assert.equal(f.toggle.attributes["aria-expanded"], "true");
    assert.equal(f.key("Escape").prevented, true);
    assert.equal(f.document.activeElement, f.toggle);
    assert.equal(f.main.inert, false);
    assert.equal(f.sidebar.inert, true);
    assert.equal(f.backdrop.hidden, true);
});

test("メニュー内のTab移動は閉じるボタンとログアウト間で循環する", () => {
    const f = fixture(); f.toggle.dispatch("click");
    f.key("Tab", true); assert.equal(f.document.activeElement, f.logout);
    f.key("Tab"); assert.equal(f.document.activeElement, f.close);
});

test("確認ダイアログのEscapeを奪わず、背景クリックで閉じる", () => {
    const f = fixture(); f.toggle.dispatch("click"); f.setDialog(true);
    assert.equal(f.key("Escape").prevented, false);
    assert.equal(f.main.inert, true);
    f.setDialog(false); f.backdrop.dispatch("click");
    assert.equal(f.main.inert, false);
    assert.equal(f.backdrop.hidden, true);
});

test("幅変更時に遮蔽を残さず、デスクトップのナビゲーションを利用できる", () => {
    const f = fixture(); f.toggle.dispatch("click");
    f.media.matches = false; f.media.change();
    assert.equal(f.sidebar.inert, false);
    assert.equal(f.main.inert, false);
    assert.equal(f.backdrop.hidden, true);
    f.link.focus(); f.media.matches = true; f.media.change();
    assert.equal(f.document.activeElement, f.toggle);
    assert.equal(f.sidebar.inert, true);
});

test("デスクトップは常時ナビゲーションを表示し、オーバーレイを開かない", () => {
    const f = fixture(false); f.toggle.dispatch("click");
    assert.equal(f.sidebar.inert, false);
    assert.equal(f.main.inert, false);
    assert.equal(f.toggle.attributes["aria-expanded"], "false");
    assert.equal(f.backdrop.hidden, true);
});
