document.addEventListener("DOMContentLoaded", function () {
    const passwordInput = document.getElementById("password");
    const passwordToggle = document.getElementById("passwordToggle");

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

    updateClock();
    window.setInterval(updateClock, 1000);
});
