/* 全画面の同一オリジン更新要求へ共通CSRFを付加する。再送信は行わない。 */
(() => {
    "use strict";
    $.ajaxPrefilter(function (options) {
        const original = options.beforeSend;
        options.beforeSend = function (xhr, settings) {
            const target = new URL(settings.url, window.location.href);
            if (target.origin === window.location.origin && !/^(GET|HEAD|OPTIONS|TRACE)$/i.test(settings.type)) {
                const token = document.querySelector('meta[name="_csrf"]')?.content;
                const name = document.querySelector('meta[name="_csrf_header"]')?.content;
                if (!token || !name) {
                    xhr.responseJSON = { success: false, code: "CSRF_UNAVAILABLE", message: "画面の安全情報を読み込めませんでした。再表示してから操作してください。" };
                    // jQueryはbeforeSendで中止するとoptionsのcallbackをまだ登録していない。
                    // 通信を行わず、各画面のエラー表示・ボタン復帰を必ず実行する。
                    if (settings.error) xhr.fail(settings.error);
                    if (settings.complete) xhr.always(() => settings.complete.call(this, xhr, "canceled"));
                    return false;
                }
                xhr.setRequestHeader(name, token);
            }
            return original ? original.call(this, xhr, settings) : undefined;
        };
    });

    $(document).on("submit", "form[data-logout]", function (event) {
        event.preventDefault();
        const form = this;
        const button = form.querySelector("button");
        if (button.disabled) return;
        const releaseFeedback = window.PortalFeedback?.busy(button, "ログアウト中") || (() => {});
        button.disabled = true;
        $.ajax({ url: form.action, type: "POST", data: $(form).serialize(), dataType: "json",
            success: function (result) { if (result.success) window.location.assign("/"); },
            error: function (xhr) { PortalDialog.info(xhr.responseJSON?.message || "ログアウトできませんでした。画面を再表示してください。"); },
            complete: function () { releaseFeedback(); button.disabled = false; }
        });
    });

    $(document).ready(function () {
        const activityUrl = document.querySelector('meta[name="security_activity_url"]')?.content;
        if (!activityUrl) return;
        let lastActivity = Date.now();
        // タイマーでは延長しない。入力・クリック等の実操作時に最大1分に1回だけ通知する。
        function activity(event) {
            if (!event.isTrusted || document.hidden || Date.now() - lastActivity < 60000) return;
            lastActivity = Date.now();
            $.ajax({ url: activityUrl, type: "POST", dataType: "json", headers: { "X-Requested-With": "XMLHttpRequest" },
                error: function (xhr) {
                    if (xhr.status === 401) window.location.assign("/");
                    else if (xhr.status === 403) PortalDialog.info(xhr.responseJSON?.message || "画面を再表示してください。");
                }
            });
        }
        for (const type of ["pointerdown", "keydown", "input", "wheel"]) document.addEventListener(type, activity, { passive: true });
    });
})();
