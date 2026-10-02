document.addEventListener("DOMContentLoaded", function () {
    const clockElement = document.getElementById("currentTime");

    function updateClock() {
        const now = new Date();
        const date = new Intl.DateTimeFormat("ja-JP", {
            timeZone: "Asia/Tokyo",
            year: "numeric",
            month: "long",
            day: "numeric",
            weekday: "short",
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
            hour12: false
        }).format(now);

        clockElement.textContent = date;
    }

    updateClock();
    window.setInterval(updateClock, 1000);
});
