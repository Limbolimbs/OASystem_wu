document.addEventListener("DOMContentLoaded", function () {
    const userNameInput = document.getElementById("userName");
    const passwordInput = document.getElementById("password");
    const loginButton = document.querySelector(".login-button");
    const errorMessage = document.querySelector(".error-message");

    function showError(message) {
        errorMessage.textContent = message;
        errorMessage.hidden = false;
    }

    function clearError() {
        errorMessage.textContent = "";
        errorMessage.hidden = true;
    }

    async function login() {
        const userName = userNameInput.value.trim();
        const password = passwordInput.value;

        clearError();

        if (!userName || !password) {
            showError("ユーザー名とパスワードを入力してください。");
            return;
        }

        loginButton.disabled = true;
        loginButton.textContent = "ログイン中...";

        try {
            const requestBody = new URLSearchParams({
                userName: userName,
                password: password
            });

            const response = await fetch("/users/login", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                },
                body: requestBody
            });

            if (!response.ok) {
                throw new Error("Login request failed");
            }

            const result = (await response.text()).trim();

            if (result === "OK") {
                window.location.href = "/home";
                return;
            }

            showError("ユーザー名またはパスワードが正しくありません。");
        } catch (error) {
            showError("通信エラーが発生しました。しばらくしてから再度お試しください。");
        } finally {
            loginButton.disabled = false;
            loginButton.textContent = "ログイン";
        }
    }

    loginButton.addEventListener("click", login);

    [userNameInput, passwordInput].forEach(function (input) {
        input.addEventListener("keydown", function (event) {
            if (event.key === "Enter") {
                event.preventDefault();
                login();
            }
        });
    });

    clearError();
});
