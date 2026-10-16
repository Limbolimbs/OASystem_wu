/* 業務画面の狭幅ナビゲーション。業務通信・権限判定は行わない。 */
(() => {
    "use strict";
    const sidebar = document.getElementById("portalNavigation");
    const toggle = document.querySelector("[data-navigation-toggle]");
    const closeButton = document.querySelector("[data-navigation-close]");
    const backdrop = document.querySelector("[data-navigation-backdrop]");
    const content = document.querySelector(".main-area");
    if (!sidebar || !toggle || !closeButton || !backdrop || !content) return;

    const narrow = window.matchMedia("(max-width: 760px)");
    let opened = false;

    function setOpen(value, restoreFocus = true) {
        opened = value && narrow.matches;
        // inertを適用する前に、閉じる側のフォーカスを本文へ戻す。
        if (!opened) content.inert = false;
        if (!opened && restoreFocus && sidebar.contains(document.activeElement)) toggle.focus();
        document.body.classList.toggle("navigation-open", opened);
        toggle.setAttribute("aria-expanded", String(opened));
        toggle.setAttribute("aria-label", opened ? "メニューを閉じる" : "メニューを開く");
        backdrop.hidden = !opened;
        sidebar.inert = narrow.matches && !opened;
        content.inert = opened;
        if (opened) closeButton.focus();
    }

    toggle.addEventListener("click", () => setOpen(!opened));
    closeButton.addEventListener("click", () => setOpen(false));
    backdrop.addEventListener("click", () => setOpen(false));
    sidebar.addEventListener("click", event => {
        if (event.target.closest("a[href]")) setOpen(false);
    });
    document.addEventListener("keydown", event => {
        if (!opened || document.querySelector("dialog[open]")) return;
        if (event.key === "Escape") {
            event.preventDefault();
            setOpen(false);
        } else if (event.key === "Tab") {
            const controls = Array.from(sidebar.querySelectorAll("a[href],button:not(:disabled),[tabindex='0']"))
                .filter(element => !element.hidden && element.getClientRects().length);
            const first = controls[0];
            const last = controls[controls.length - 1];
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
        }
    });
    narrow.addEventListener("change", () => setOpen(false));
    document.documentElement.classList.add("navigation-ready");
    setOpen(false, false);
})();
