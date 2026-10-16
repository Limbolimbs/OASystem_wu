/* フォーム送信・部分表示・処理状態を共通管理する。通常の画面遷移はリンクを使用する。 */
(() => {
    "use strict";
    const pendingForms = new WeakSet();
    const pendingActions = new Set();
    const pendingRegions = new Map();
    const headers = { "X-Requested-With": "XMLHttpRequest" };
    const ids = value => (value || "").split(/\s+/).filter(Boolean);

    function prepareForms(root = document) {
        root.querySelectorAll("form[data-ajax]").forEach(form => { form.noValidate = true; });
    }
    prepareForms();
    document.addEventListener("ajax:updated", event => prepareForms(event.detail.region));

    function notice(message, error = false, retry = null) {
        let box = document.getElementById("ajax-notice");
        if (!box) {
            box = document.createElement("div");
            box.id = "ajax-notice";
            document.body.append(box);
        }
        box.className = "ajax-notice" + (error ? " ajax-error" : "");
        box.setAttribute("role", error ? "alert" : "status");
        box.replaceChildren(document.createTextNode(message));
        if (retry) {
            const button = document.createElement("button");
            button.type = "button";
            button.textContent = "表示を再取得";
            button.addEventListener("click", async () => {
                button.disabled = true;
                await retry(); // 更新処理そのものは再送信しない。
            });
            box.append(button);
        }
        const close = document.createElement("button");
        close.type = "button";
        close.textContent = "閉じる";
        close.addEventListener("click", () => box.remove());
        box.append(close);
        window.PortalFeedback?.reveal(box, { update: true });
    }

    function localUrl(value) {
        const url = new URL(value, window.location.href);
        if (url.origin !== window.location.origin) throw new Error("接続先を確認してください。");
        return url;
    }

    function responseError(xhr, textStatus) {
        let result = xhr.responseJSON;
        if (!result && (xhr.getResponseHeader("Content-Type") || "").includes("application/json")) {
            try { result = JSON.parse(xhr.responseText); } catch { /* 不正な応答は汎用メッセージにする。 */ }
        }
        if (result?.code === "CSRF_UNAVAILABLE") return new Error(result.message);
        if (xhr.status === 401) {
            // セッション切れだけはログイン画面への遷移が必要。
            window.location.assign(localUrl(result?.redirectUrl || "/").href);
        }
        if (xhr.status === 0 || textStatus === "parsererror") {
            return new Error("処理結果を確認できません。再送信する前に一覧を確認してください。");
        }
        return new Error(result?.message || (xhr.status === 403
            ? "この操作を行う権限がありません。" : "データを取得できませんでした。"));
    }

    // 別の行で入力中の承認コメントや権限選択を、部分更新後も残す。
    function drafts(region, submittedForm) {
        const values = [];
        region.querySelectorAll("form[data-ajax]").forEach(form => {
            if (form === submittedForm || pendingForms.has(form) || form.method !== "post") return;
            Array.from(form.elements).forEach((field, index) => {
                if (!field.name || ["hidden", "password", "submit"].includes(field.type)) return;
                const changed = field.tagName === "SELECT"
                    ? Array.from(field.options).some(option => option.selected !== option.defaultSelected)
                    : ["checkbox", "radio"].includes(field.type)
                        ? field.checked !== field.defaultChecked : field.value !== field.defaultValue;
                if (changed) values.push({ action: form.action, index, name: field.name,
                    value: field.value, checked: field.checked, focused: field === document.activeElement });
            });
        });
        return values;
    }

    function restoreDrafts(region, values) {
        const forms = Array.from(region.querySelectorAll("form[data-ajax]"));
        values.forEach(value => {
            const field = forms.find(form => form.action === value.action)?.elements[value.index];
            if (!field || field.name !== value.name) return;
            field.value = value.value;
            if (["checkbox", "radio"].includes(field.type)) field.checked = value.checked;
            if (value.focused) field.focus({ preventScroll: true });
        });
    }

    function refresh(id, sourceUrl, submittedForm) {
        const region = document.getElementById(id);
        if (!region) return Promise.resolve(); // 権限によって表示しない領域は取得しない。
        const url = localUrl(sourceUrl || region.dataset.ajaxUrl);
        url.searchParams.set("fragment", id);
        pendingRegions.get(id)?.xhr?.abort();
        const request = { xhr: null };
        pendingRegions.set(id, request);
        region.setAttribute("aria-busy", "true");
        // Promiseは複数領域の完了待ちに使い、通信はすべてjQueryの$.ajaxで行う。
        return new Promise(function (resolve, reject) {
            request.xhr = $.ajax({
                url: url.href,
                type: "GET",
                dataType: "html",
                headers: headers,
                cache: false,
                success: function (html) {
                    try {
                        if (pendingRegions.get(id) !== request) { resolve(); return; }
                        const parsed = new DOMParser().parseFromString(html, "text/html");
                        const replacement = parsed.getElementById(id);
                        if (!replacement?.hasAttribute("data-ajax-region")) {
                            throw new Error("表示データの形式が正しくありません。");
                        }
                        // jQuery.html()ではなく、検証した同一オリジンの断片のみ取り込む。
                        const current = document.getElementById(id);
                        const values = drafts(current, submittedForm);
                        const next = document.importNode(replacement, true);
                        current.replaceWith(next);
                        restoreDrafts(next, values);
                        document.dispatchEvent(new CustomEvent("ajax:updated", { detail: { id, region: next } }));
                        resolve();
                    } catch (error) { reject(error); }
                },
                error: function (xhr, textStatus) {
                    if (textStatus === "abort") resolve();
                    else reject(responseError(xhr, textStatus));
                },
                complete: function () {
                    if (pendingRegions.get(id) === request) {
                        pendingRegions.delete(id);
                        document.getElementById(id)?.removeAttribute("aria-busy");
                    }
                }
            });
        });
    }

    async function refreshAll(targets, sourceUrl, submittedForm, resetPages = []) {
        const results = await Promise.allSettled(targets.map(id => {
            const region = document.getElementById(id);
            if (!region) return Promise.resolve();
            const url = localUrl(sourceUrl || region.dataset.ajaxUrl);
            if (resetPages.includes(id) && region.dataset.ajaxPageParam) {
                url.searchParams.set(region.dataset.ajaxPageParam, "0");
            }
            return refresh(id, url, submittedForm);
        }));
        return results.flatMap((result, index) => result.status === "rejected"
            ? [{ id: targets[index], message: result.reason.message }] : []);
    }

    function setBusy(form) {
        const key = form.closest("[data-ajax-lock]")?.dataset.ajaxLock || form.action;
        pendingActions.add(key);
        const controls = Array.from(form.elements).map(field => [field, field.disabled]);
        form.setAttribute("aria-busy", "true");
        const releaseFeedback = window.PortalFeedback?.busy(form.querySelector('button[type="submit"], button:not([type])'),
            form.method === "get" ? "取得中" : "処理中") || (() => {});
        controls.forEach(([field]) => { field.disabled = true; });
        return () => {
            releaseFeedback();
            form.removeAttribute("aria-busy");
            controls.forEach(([field, disabled]) => { field.disabled = disabled; });
            pendingForms.delete(form);
            pendingActions.delete(key);
            form.dispatchEvent(new CustomEvent("ajax:idle", { bubbles: true }));
        };
    }

    async function query(targets, url) {
        const failed = await refreshAll(targets, url);
        if (failed.length) notice(failed[0].message || "表示を更新できませんでした。", true,
            () => query(failed.map(item => item.id), url));
    }

    $(document).on("submit", "form[data-ajax]", async function (event) {
        const form = event.target;
        event.preventDefault();
        const key = form.closest("[data-ajax-lock]")?.dataset.ajaxLock || form.action;
        if (pendingForms.has(form) || pendingActions.has(key)) return;
        if (!window.PortalDialog) { notice("画面の共通機能を読み込めませんでした。ページを再表示してください。", true); return; }
        if (!PortalDialog.validateForm(form)) return;
        pendingForms.add(form);
        if (form.dataset.confirmMessage) {
            try {
                const accepted = await PortalDialog.confirm(form.dataset.confirmMessage, {
                    title: "操作内容の確認", confirmLabel: "確認して実行", danger: /無効化|取り下げ|削除/.test(form.dataset.confirmMessage)
                });
                if (!accepted || !form.isConnected) { pendingForms.delete(form); return; }
            } catch {
                pendingForms.delete(form);
                notice("確認画面を表示できませんでした。操作は実行していません。", true);
                return;
            }
            // 確認中に別フォームが同じ対象を処理した場合も二重送信しない。
            if (pendingActions.has(key) || !PortalDialog.validateForm(form)) { pendingForms.delete(form); return; }
        }
        // disabled化する前に値を読む。同名の曜日チェックボックスもすべて送信する。
        const data = $(form).serializeArray();
        const targets = ids(form.dataset.ajaxTargets);
        const release = setBusy(form);
        try {
            if (form.method === "get") {
                const url = localUrl(form.action);
                data.forEach(field => url.searchParams.append(field.name, field.value));
                query(targets, url).catch(error => notice(error.message, true)).finally(release);
                return;
            }
            $.ajax({
                url: localUrl(form.action).href,
                type: "POST",
                data: data,
                dataType: "json",
                headers: headers,
                success: async function (result, textStatus, xhr) {
                    let saved = false;
                    try {
                        if (!(xhr.getResponseHeader("Content-Type") || "").includes("application/json")
                                || !result || typeof result.success !== "boolean") {
                            throw new Error("処理結果を確認できません。再送信する前に一覧を確認してください。");
                        }
                        if (!result.success) throw new Error(result.message || "操作を完了できませんでした。");
                        saved = true;
                        if (form.hasAttribute("data-ajax-reset")) form.reset();
                        form.dispatchEvent(new CustomEvent("ajax:success", { bubbles: true, detail: result }));
                        const resetPages = ids(form.dataset.ajaxResetPages);
                        const failed = await refreshAll(targets, null, form, resetPages);
                        async function retryDisplay() {
                            const remaining = await refreshAll(failed.map(item => item.id), null, form, resetPages);
                            if (remaining.length) notice("操作は完了していますが、表示を更新できませんでした。", true, retryDisplay);
                            else notice(result.message);
                        }
                        if (failed.length) notice(result.message + " 表示の更新に失敗しました。操作の再送信は不要です。", true, retryDisplay);
                        else notice(result.message);
                    } catch (error) {
                        notice(saved ? "操作は完了しています。表示を再取得してください。" : error.message, true);
                    } finally { release(); }
                },
                error: function (xhr, textStatus) {
                    notice(responseError(xhr, textStatus).message, true);
                    release();
                }
            });
        } catch (error) {
            notice(error.message, true);
            release();
        }
    });

    $(document).on("click", "a[data-ajax-targets]", function (event) {
        const link = this;
        if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        query(ids(link.dataset.ajaxTargets), localUrl(link.href)).catch(error => notice(error.message, true));
    });
})();
