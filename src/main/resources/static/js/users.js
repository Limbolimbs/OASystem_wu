document.addEventListener("DOMContentLoaded", function () {
    const searchInput = document.getElementById("employeeSearch");
    const visibleCount = document.getElementById("visibleCount");
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

    function filterEmployees() {
        const employeeRows = Array.from(document.querySelectorAll(".employee-row"));
        const noSearchResult = document.getElementById("noSearchResult");
        const keyword = searchInput.value.trim().toLowerCase();
        let count = 0;

        employeeRows.forEach(function (row) {
            const searchableText = [
                row.dataset.username,
                row.dataset.realname,
                row.dataset.department,
                row.dataset.role,
                row.querySelector(".role-badge")?.textContent
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
    }

    searchInput.addEventListener("input", filterEmployees);
    document.addEventListener("ajax:updated", function (event) {
        if (event.detail.id !== "employee-data") return;
        filterEmployees();
        lastUpdated.textContent = new Date().toLocaleTimeString("ja-JP", { hour12: false });
    });

    updateClock();
    lastUpdated.textContent =
        new Date().toLocaleTimeString("ja-JP", { hour12: false });
    window.setInterval(updateClock, 1000);
});
