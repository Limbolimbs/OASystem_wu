$(document).ready(function () {
    function updateClock() {
        $("#currentTime").text(new Intl.DateTimeFormat("ja-JP", {
            year: "numeric", month: "long", day: "numeric", weekday: "short",
            hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
        }).format(new Date()));
    }
    updateClock();
    setInterval(updateClock, 1000);
});
