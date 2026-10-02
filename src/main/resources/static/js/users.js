document.addEventListener("DOMContentLoaded", function () {
    const searchInput = document.getElementById("employeeSearch");
    const employeeRows = Array.from(document.querySelectorAll(".employee-row"));
    const visibleCount = document.getElementById("visibleCount");
    const noSearchResult = document.getElementById("noSearchResult");
    const lastUpdated = document.getElementById("lastUpdated");

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

    document.querySelectorAll(".js-confirm-form").forEach(function (form) {
        form.addEventListener("submit", function (event) {
            const message = form.dataset.confirmMessage;

            if (message && !window.confirm(message)) {
                event.preventDefault();
            }
        });
    });

    searchInput.addEventListener("input", function () {
        const keyword = this.value.trim().toLowerCase();
        let count = 0;

        employeeRows.forEach(function (row) {
            const searchableText = [
                row.dataset.username,
                row.dataset.realname,
                row.dataset.department,
                row.dataset.role
            ].join(" ").toLowerCase();

            const matched = searchableText.includes(keyword);
            row.style.display = matched ? "" : "none";

            if (matched) {
                count++;
            }
        });

        visibleCount.textContent = count;
        noSearchResult.style.display =
            employeeRows.length > 0 && count === 0 ? "block" : "none";
    });

    updateClock();
    lastUpdated.textContent =
        new Date().toLocaleTimeString("ja-JP", { hour12: false });
    window.setInterval(updateClock, 1000);
});
