$(document).ready(function () {
    const userNameInput = $("#userName");
    const passwordInput = $("#password");
    const loginButton = $(".login-button");
    const errorMessage = $(".error-message");
    let submitting = false;

    function showError(message) {
        const change = () => errorMessage.text(message).prop("hidden", false);
        if (window.PortalFeedback) PortalFeedback.layout(document.querySelectorAll(".login-container, .login-box > .login-button, .login-box > .security-note"), change);
        else change();
    }

    function clearError() {
        const change = () => errorMessage.text("").prop("hidden", true);
        if (window.PortalFeedback) PortalFeedback.layout(document.querySelectorAll(".login-container, .login-box > .login-button, .login-box > .security-note"), change);
        else change();
    }

    function login() {
        if (submitting) return;
        const userName = userNameInput.val().trim();
        const password = passwordInput.val();

        clearError();

        if (!userName || !password) {
            showError("ログインIDとパスワードを入力してください。");
            return;
        }

        submitting = true;
        loginButton.prop("disabled", true).text("ログイン中...");
        loginButton.attr("aria-busy", "true");
        const releaseFeedback = window.PortalFeedback?.busy(loginButton[0], "ログイン中...") || (() => {});

        $.ajax({
            url: "/users/login",
            type: "POST",
            headers: { "X-Requested-With": "XMLHttpRequest" },
            data: {
                userName: userName,
                password: password
            },
            dataType: "text",
            success: function (data) {
                if (data.trim() === "OK") {
                    window.location.href = "/home";
                } else {
                    showError("ログインIDまたはパスワードが正しくありません。");
                }
            },
            error: function (xhr) {
                let result = xhr.responseJSON;
                if (!result && (xhr.getResponseHeader("Content-Type") || "").includes("application/json")) {
                    try { result = JSON.parse(xhr.responseText); } catch { }
                }
                const retry = Number(xhr.getResponseHeader("Retry-After"));
                const wait = xhr.status === 429 && retry > 0 ? ` 再試行まで約${Math.ceil(retry / 60)}分です。` : "";
                showError((result?.message || "通信エラーが発生しました。しばらくしてから再度お試しください。") + wait);
            },
            complete: function () {
                releaseFeedback();
                submitting = false;
                loginButton.removeAttr("aria-busy");
                loginButton.prop("disabled", false).text("ログイン");
            }
        });
    }

    loginButton.on("click", login);

    userNameInput.add(passwordInput).on("keydown", function (event) {
        if (event.key === "Enter") {
            event.preventDefault();
            login();
        }
    });

    clearError();
});
