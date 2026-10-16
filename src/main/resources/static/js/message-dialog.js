/* 確認・案内・入力検証をポータル内で表示する。ブラウザーの警告ウィンドウは使用しない。 */
(() => {
    "use strict";
    let dialog;
    let finish;
    let previousFocus;
    let closing = false;
    let validationSequence = 0;

    function createDialog() {
        if (dialog) return;
        dialog = document.createElement("dialog");
        dialog.id = "portal-message-dialog";
        dialog.className = "message-dialog";
        dialog.setAttribute("aria-labelledby", "message-dialog-title");
        dialog.setAttribute("aria-describedby", "message-dialog-body");
        dialog.innerHTML = '<div class="message-dialog-heading"><div><p>企業OAシステム</p>'
            + '<h2 id="message-dialog-title"></h2></div><button type="button" class="message-dialog-close" aria-label="閉じる">×</button></div>'
            + '<p id="message-dialog-body" class="message-dialog-body"></p><div class="message-dialog-actions">'
            + '<button type="button" class="message-dialog-cancel">キャンセル</button>'
            + '<button type="button" class="message-dialog-accept">続行する</button></div>';
        document.body.append(dialog);
        async function close(value) {
            if (!finish || closing) return;
            closing = true;
            const resolve = finish;
            try { await window.PortalFeedback?.dismiss(dialog); } catch { /* 動作の不備で確認操作を止めない。 */ }
            finish = null;
            closing = false;
            if (dialog.open) dialog.close();
            if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true });
            resolve(value);
        }
        dialog.querySelector(".message-dialog-cancel").addEventListener("click", () => close(false));
        dialog.querySelector(".message-dialog-close").addEventListener("click", () => close(false));
        dialog.querySelector(".message-dialog-accept").addEventListener("click", () => close(true));
        dialog.addEventListener("cancel", event => { event.preventDefault(); close(false); });
        dialog.addEventListener("close", () => { if (finish && !dialog.open) close(false); });
        dialog.addEventListener("click", event => {
            if (event.target !== dialog) return;
            const box = dialog.getBoundingClientRect();
            if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) close(false);
        });
    }

    function show(message, options = {}) {
        createDialog();
        // 同時に別操作が求めた確認は拒否し、誤った操作の承認を防ぐ。
        if (finish) return Promise.resolve(false);
        return new Promise((resolve, reject) => {
            finish = resolve;
            previousFocus = document.activeElement;
            dialog.querySelector("#message-dialog-title").textContent = options.title || "操作の確認";
            dialog.querySelector("#message-dialog-body").textContent = message;
            const cancel = dialog.querySelector(".message-dialog-cancel");
            const accept = dialog.querySelector(".message-dialog-accept");
            cancel.hidden = Boolean(options.info);
            cancel.textContent = options.cancelLabel || "キャンセル";
            accept.textContent = options.confirmLabel || (options.info ? "閉じる" : "続行する");
            accept.classList.toggle("message-dialog-danger", Boolean(options.danger));
            try {
                dialog.showModal();
                (options.info ? accept : cancel).focus();
            } catch (error) { finish = null; reject(error); }
        });
    }

    function validationMessage(field) {
        const label = (field.labels?.[0]?.textContent || "入力項目").replace(/\*/g, "").trim();
        const validity = field.validity;
        if (validity.customError) return field.validationMessage;
        if (validity.valueMissing) return `${label}を${["checkbox", "radio"].includes(field.type) || field.tagName === "SELECT" ? "選択" : "入力"}してください。`;
        if (validity.rangeUnderflow) return `${label}は${field.min}以上で入力してください。`;
        if (validity.rangeOverflow) return `${label}は${field.max}以下で入力してください。`;
        if (validity.tooShort) return `${label}は${field.minLength}文字以上で入力してください。`;
        if (validity.tooLong) return `${label}は${field.maxLength}文字以内で入力してください。`;
        return `${label}の形式・入力値を確認してください。`;
    }

    function validateForm(form) {
        form.noValidate = true;
        form.querySelectorAll(".field-error").forEach(node => node.remove());
        Array.from(form.elements).forEach(field => {
            field.removeAttribute("aria-invalid"); field.removeAttribute("aria-errormessage");
        });
        const invalid = Array.from(form.elements).filter(field => field.willValidate && !field.checkValidity());
        invalid.forEach(field => {
            field.setAttribute("aria-invalid", "true");
            const error = document.createElement("p");
            error.className = "field-error";
            error.id = "form-validation-" + (++validationSequence);
            error.setAttribute("role", "alert");
            error.textContent = validationMessage(field);
            field.setAttribute("aria-errormessage", error.id);
            (field.closest(".password-field") || field).insertAdjacentElement("afterend", error);
        });
        invalid[0]?.focus({ preventScroll: false });
        return invalid.length === 0;
    }

    window.PortalDialog = { confirm: (message, options) => show(message, options),
        info: (message, title = "お知らせ") => show(message, { info: true, title }), validateForm };
})();
