document.addEventListener("DOMContentLoaded", function () {
    const passwordInput = document.getElementById("password");
    const passwordToggle = document.getElementById("passwordToggle");
    const confirmation = document.getElementById("passwordConfirmation");
    const minimum = Number(passwordInput.dataset.passwordMinlength || 12);
    const maximum = Number(passwordInput.dataset.passwordMaxlength || 128);
    const categoriesRequired = Number(passwordInput.dataset.passwordCategories || 3);

    function checkPassword() {
        const value = passwordInput.value;
        const categories = [/[A-Z]/, /[a-z]/, /[0-9]/, /[!-/:-@\[-`{-~]/].filter(rule => rule.test(value)).length;
        let message = "";
        if (!value) message = "初期パスワードを入力してください。";
        else if (Array.from(value).length < minimum || Array.from(value).length > maximum) message = `パスワードは${minimum}～${maximum}文字で入力してください。`;
        else if (/[\u0000-\u001f\u007f-\u009f\u2028\u2029]/u.test(value) || Array.from(value).some(character => /^[\ud800-\udfff]$/u.test(character))) message = "パスワードに改行や制御文字は使用できません。";
        else if (categories < categoriesRequired) message = "英大文字・英小文字・数字・記号の4種類のうち、3種類以上を含めてください。";
        passwordInput.setCustomValidity(message);
        if (confirmation) confirmation.setCustomValidity(!confirmation.value ? "確認用パスワードを入力してください。"
            : confirmation.value !== value ? "パスワードと確認用パスワードが一致しません。" : "");
    }
    passwordInput.addEventListener("input", checkPassword);
    confirmation?.addEventListener("input", checkPassword);
    passwordInput.form.addEventListener("submit", checkPassword);
    passwordInput.form.addEventListener("reset", () => {
        passwordInput.setCustomValidity("");
        confirmation?.setCustomValidity("");
        passwordInput.form.querySelectorAll(".field-error").forEach(node => node.remove());
        Array.from(passwordInput.form.elements).forEach(field => {
            field.removeAttribute("aria-invalid"); field.removeAttribute("aria-errormessage");
        });
    });

    function updateClock() {
        const now = new Date();
        const date = new Intl.DateTimeFormat("ja-JP", {
            year: "numeric",
            month: "long",
            day: "numeric",
            weekday: "short"
        }).format(now);

        document.getElementById("currentTime").textContent =
            date + " " + now.toLocaleTimeString("ja-JP", { hour12: false });
    }

    passwordToggle.addEventListener("click", function () {
        const passwordIsHidden = passwordInput.type === "password";
        passwordInput.type = passwordIsHidden ? "text" : "password";
        this.textContent = passwordIsHidden ? "非表示" : "表示";
        this.setAttribute(
            "aria-label",
            passwordIsHidden ? "パスワードを非表示" : "パスワードを表示"
        );
    });

    passwordInput.form.addEventListener("ajax:success", function () {
        passwordInput.type = "password";
        passwordToggle.textContent = "表示";
        passwordToggle.setAttribute("aria-label", "パスワードを表示");
    });

    updateClock();
    window.setInterval(updateClock, 1000);
});
