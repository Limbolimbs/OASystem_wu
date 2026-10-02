document.addEventListener("DOMContentLoaded", function () {
    const clock = document.getElementById("currentTime");
    function updateClock() {
        if (!clock) return;
        clock.textContent = new Intl.DateTimeFormat("ja-JP", {
            timeZone: "Asia/Tokyo", year: "numeric", month: "long", day: "numeric",
            weekday: "short", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
        }).format(new Date());
    }
    updateClock();
    window.setInterval(updateClock, 1000);

    const type = document.getElementById("requestType");
    function updateTypeFields() {
        if (!type) return;
        document.querySelectorAll(".correction-field").forEach(field => {
            field.hidden = type.value !== "CORRECTION";
            if (field.hidden) field.querySelector("input").value = "";
        });
        document.querySelectorAll(".overtime-field").forEach(field => {
            field.hidden = type.value !== "OVERTIME";
            if (field.hidden) field.querySelector("input").value = "";
        });
    }
    if (type) {
        type.addEventListener("change", updateTypeFields);
        updateTypeFields();
    }
});
