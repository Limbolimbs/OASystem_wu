/* 操作の見た目だけを管理する。通信・認証・成功判定・再送信は行わない。 */
(() => {
    "use strict";
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)");
    const pending = new WeakMap();
    const ripples = new WeakMap();
    const reveals = new WeakMap();
    const layoutAnimations = new WeakMap();
    const exits = new WeakMap();
    const activeAnimations = new Set();
    const ease = "cubic-bezier(.16, 1, .3, 1)";
    const motionAllowed = () => !reduced.matches && !document.hidden;

    function animate(element, frames, options) {
        if (!element?.animate || !motionAllowed()) return null;
        const animation = element.animate(frames, options);
        activeAnimations.add(animation);
        animation.finished.catch(() => {}).finally(() => activeAnimations.delete(animation));
        return animation;
    }
    function stopMotion() {
        document.documentElement.classList.toggle("ui-motion-paused", !motionAllowed());
        if (motionAllowed()) return;
        for (const animation of activeAnimations) animation.cancel();
    }
    reduced.addEventListener("change", stopMotion);
    document.addEventListener("visibilitychange", stopMotion);
    stopMotion();

    function reveal(element, options = {}) {
        if (!element || element.contains(document.activeElement)) return;
        reveals.get(element)?.cancel();
        const frames = options.update ? [{ opacity: .7 }, { opacity: 1 }]
            : [{ opacity: 0, transform: `translateY(${options.distance || 18}px)` }, { opacity: 1, transform: "translateY(0)" }];
        const animation = animate(element, frames, { duration: options.duration || (options.update ? 220 : 520),
            delay: options.delay || 0, easing: ease, fill: "backwards" });
        if (animation) reveals.set(element, animation);
    }

    /** メッセージ挿入前後の位置差だけを補間する。高さを毎フレーム再計算しない。 */
    function layout(elements, change) {
        const targets = Array.from(elements || []).filter(element => element?.isConnected);
        const before = targets.map(element => element.getBoundingClientRect());
        targets.forEach(element => layoutAnimations.get(element)?.cancel());
        const result = change();
        if (!motionAllowed()) return result;
        const after = targets.map(element => element.getBoundingClientRect());
        targets.forEach((element, index) => {
            const ancestor = targets.findIndex(other => other !== element && other.contains(element));
            const parentX = ancestor < 0 ? 0 : before[ancestor].left - after[ancestor].left;
            const parentY = ancestor < 0 ? 0 : before[ancestor].top - after[ancestor].top;
            const x = before[index].left - after[index].left - parentX, y = before[index].top - after[index].top - parentY;
            if (!after[index].width || !after[index].height || (Math.abs(x) < 1 && Math.abs(y) < 1)) return;
            const animation = animate(element, [{ transform: `translate(${x}px, ${y}px)` }, { transform: "translate(0, 0)" }],
                { duration: 340, easing: ease });
            if (animation) layoutAnimations.set(element, animation);
        });
        return result;
    }

    function clearRipple(button) {
        const previous = ripples.get(button);
        if (!previous) return;
        previous.animation?.cancel(); previous.node.remove(); ripples.delete(button);
    }
    function ripple(event) {
        const button = event.target.closest?.("button, .primary-button, .business-secondary");
        if (!button || button.disabled || button.getAttribute("aria-busy") === "true" || !motionAllowed()) return;
        if (event.type === "keydown" && (event.repeat || !["Enter", " "].includes(event.key))) return;
        clearRipple(button);
        const box = button.getBoundingClientRect();
        const diameter = 2 * Math.hypot(box.width, box.height);
        const node = document.createElement("span"); node.className = "button-ripple"; node.setAttribute("aria-hidden", "true");
        const pointer = event.type === "pointerdown";
        node.style.width = node.style.height = diameter + "px";
        node.style.left = (pointer ? event.clientX - box.left : box.width / 2) - diameter / 2 + "px";
        node.style.top = (pointer ? event.clientY - box.top : box.height / 2) - diameter / 2 + "px";
        button.append(node);
        const animation = animate(node, [{ transform: "scale(.05)", opacity: .16 }, { transform: "scale(1)", opacity: 0 }],
            { duration: 400, easing: "ease-out" });
        ripples.set(button, { node, animation });
        const cleanup = () => { node.remove(); if (ripples.get(button)?.node === node) ripples.delete(button); };
        if (animation) animation.finished.then(cleanup, cleanup); else cleanup();
    }
    document.addEventListener("pointerdown", ripple, { passive: true });
    document.addEventListener("keydown", ripple);

    /** ボタンの有効/無効は呼び出し元が管理する。元の子要素・ARIA・幅を必ず戻す。 */
    function busy(button, label = "処理中") {
        if (!button || button.tagName !== "BUTTON") return () => {};
        if (pending.has(button)) return pending.get(button);
        clearRipple(button);
        const nodes = Array.from(button.childNodes);
        const minimum = button.style.minWidth;
        const aria = button.getAttribute("aria-busy");
        button.style.minWidth = `min(100%, ${Math.ceil(button.getBoundingClientRect().width)}px)`;
        const spinner = document.createElement("span"); spinner.className = "button-spinner"; spinner.setAttribute("aria-hidden", "true");
        const caption = document.createElement("span"); caption.className = "button-caption"; caption.textContent = label;
        button.replaceChildren(spinner, caption); button.classList.add("ui-pending"); button.setAttribute("aria-busy", "true");
        let released = false;
        const release = () => {
            if (released) return;
            released = true;
            button.replaceChildren(...nodes); button.classList.remove("ui-pending"); button.style.minWidth = minimum;
            if (aria === null) button.removeAttribute("aria-busy"); else button.setAttribute("aria-busy", aria);
            pending.delete(button);
        };
        pending.set(button, release);
        return release;
    }

    /** 短い退出動作。取消・非表示・動作軽減・API不在でも完了を返す。 */
    function dismiss(element) {
        if (!element?.open) return Promise.resolve();
        if (exits.has(element)) return exits.get(element);
        const current = getComputedStyle(element);
        const start = { opacity: current.opacity, transform: current.transform };
        element.classList.add("is-closing");
        const animation = animate(element, [start,
            { opacity: 0, transform: "translateY(8px) scale(.98)" }], { duration: 180, easing: "cubic-bezier(.4, 0, 1, 1)" });
        if (!animation) { element.classList.remove("is-closing"); return Promise.resolve(); }
        const result = new Promise(resolve => {
            let settled = false;
            const finish = () => {
                if (settled) return;
                settled = true; clearTimeout(timeout); animation.cancel(); element.classList.remove("is-closing"); exits.delete(element); resolve();
            };
            const timeout = setTimeout(finish, 240);
            animation.finished.then(finish, finish);
        });
        exits.set(element, result);
        return result;
    }
    async function closeDialog(dialog) { await dismiss(dialog); if (dialog.open) dialog.close(); }

    window.PortalFeedback = { busy, reveal, layout, dismiss, closeDialog };
    document.addEventListener("ajax:updated", event => reveal(event.detail?.region, { update: true }));
    function entrance() {
        const login = document.querySelector(".login-container");
        if (login) {
            reveal(login, { duration: 620, distance: 24 });
            document.querySelectorAll(".brand-header, .brand-message, .brand-footer, .login-box > header, .login-box > .form-group, .login-button, .security-note")
                .forEach((target, index) => reveal(target, { duration: 520, distance: 14, delay: 65 + index * 40 }));
            return;
        }
        const targets = document.querySelectorAll(".content > .page-hero, .content > .overview-row > article, .content > .profile-grid > section, .content > .kpi-grid > article, .content > .table-panel, .content > .business-panel, .content > .manage-panel, .content > .profile-panel");
        let visible = 0;
        const observer = typeof IntersectionObserver === "function" && motionAllowed()
            ? new IntersectionObserver(entries => {
                entries.forEach(entry => {
                    if (!entry.isIntersecting) return;
                    observer.unobserve(entry.target); reveal(entry.target);
                });
            }, { threshold: .05 }) : null;
        Array.from(targets).forEach(target => {
            if (target.getBoundingClientRect().top < innerHeight) reveal(target, { delay: Math.min(visible++ * 55, 165) });
            else observer?.observe(target);
        });
        window.addEventListener("pagehide", () => observer?.disconnect(), { once: true });
    }
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", entrance, { once: true });
    else entrance();
})();
