/* 本人のパスワード設定。送信はjQuery AJAXのみ。認証情報は保存・記録しない。 */
$(document).ready(function () {
    "use strict";
    const form = document.getElementById("passwordForm");
    if (!form || !window.PortalDialog) return;
    const current = form.elements.currentPassword;
    const next = form.elements.newPassword;
    const confirmation = form.elements.passwordConfirmation;
    const submit = form.querySelector('[type="submit"]');
    const reset = form.querySelector('[type="reset"]');
    const message = document.getElementById("password-message");
    let busy = false;
    let completed = false;
    submit.disabled = false;

    function showMessage(text, error) {
        message.textContent = text;
        message.hidden = false;
        message.classList.toggle("is-error", Boolean(error));
        message.setAttribute("role", error ? "alert" : "status");
        message.focus();
    }

    function validate() {
        for (const field of [current, next, confirmation]) field.setCustomValidity("");
        const value = next.value;
        const categories = [/[A-Z]/, /[a-z]/, /[0-9]/, /[!-/:-@\[-`{-~]/].filter(rule => rule.test(value)).length;
        const length = Array.from(value).length;
        if (value && (length < Number(form.dataset.minLength) || length > Number(form.dataset.maxLength))) {
            next.setCustomValidity("新しいパスワードは12～128文字で入力してください。");
        } else if (/[\u0000-\u001f\u007f-\u009f\u2028\u2029]/.test(value)) {
            next.setCustomValidity("パスワードに改行や制御文字は使用できません。");
        } else if (value && categories < 3) {
            next.setCustomValidity("英大文字・英小文字・数字・記号の4種類のうち3種類以上を含めてください。");
        } else if (value && value === current.value) {
            next.setCustomValidity("現在とは異なるパスワードを設定してください。");
        }
        if (confirmation.value && value !== confirmation.value) {
            confirmation.setCustomValidity("新しいパスワードと確認用パスワードが一致しません。");
        }
        return PortalDialog.validateForm(form);
    }

    form.addEventListener("input", event => event.target.setCustomValidity?.(""));
    form.addEventListener("reset", event => {
        if (busy) { event.preventDefault(); return; }
        form.querySelectorAll(".field-error").forEach(node => node.remove());
        for (const field of [current, next, confirmation]) {
            field.setCustomValidity(""); field.removeAttribute("aria-invalid"); field.removeAttribute("aria-errormessage");
        }
        message.hidden = true;
    });
    // 履歴から復元されたページにパスワードや完了済みフォームを残さない。
    window.addEventListener("pageshow", event => { if (event.persisted) window.location.reload(); });

    $(form).on("submit", async function (event) {
        event.preventDefault();
        if (busy || completed || !validate()) return;
        busy = true;
        // 確認中はdialogが背景を非活性にする。起点をdisabledにせず、取消時のフォーカスを保つ。
        let releaseFeedback = () => {};
        function release() {
            releaseFeedback();
            busy = false;
            submit.disabled = completed;
            reset.disabled = completed;
            form.removeAttribute("aria-busy");
        }
        try {
            const accepted = await PortalDialog.confirm(
                "パスワードを変更すると、すべての端末のログインが終了します。変更しますか？",
                { title: "パスワード変更の確認", confirmLabel: "変更する" });
            if (!accepted || !validate()) { release(); return; }
            const data = $(form).serialize();
            message.hidden = true;
            releaseFeedback = window.PortalFeedback?.busy(submit, "変更中") || (() => {});
            submit.disabled = true;
            reset.disabled = true;
            form.setAttribute("aria-busy", "true");
            $.ajax({
                url: form.action, type: "POST", data: data, dataType: "json", timeout: 30000,
                success: async function (result) {
                    if (!result || result.success !== true) {
                        showMessage(result?.message || "変更を完了できませんでした。", true); return;
                    }
                    completed = true;
                    for (const field of [current, next, confirmation]) field.value = "";
                    form.querySelectorAll("input").forEach(field => { field.disabled = true; });
                    showMessage(result.message, false);
                    try { await PortalDialog.info(result.message, "パスワードを変更しました"); }
                    finally {
                        // 応答中の任意URLではなく、フォームから得たアプリ内ログイン先だけへ戻る。
                        const login = new URL(form.action);
                        login.pathname = login.pathname.replace(/my-page\/password$/, "");
                        login.search = ""; login.hash = "";
                        window.location.assign(login.href);
                    }
                },
                error: function (xhr) {
                    current.value = "";
                    const text = xhr.responseJSON?.message || (xhr.status === 0
                        ? "結果を確認できません。自動再送信は行いません。新しいパスワードでログインできるか確認してください。"
                        : "変更できませんでした。入力内容とログイン状態を確認してください。");
                    showMessage(text, true);
                    if (xhr.status === 401) {
                        completed = true;
                        for (const field of [next, confirmation]) field.value = "";
                        const link = document.createElement("a");
                        const login = new URL(form.action);
                        login.pathname = login.pathname.replace(/my-page\/password$/, "");
                        login.search = "";
                        link.href = login.href; link.textContent = " ログイン画面へ";
                        message.append(link);
                    }
                },
                complete: release
            });
        } catch {
            showMessage("操作を確認できませんでした。再送信する前にログイン状態を確認してください。", true);
            release();
        }
    });
});
