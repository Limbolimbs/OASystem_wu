const dashboardCharts = [];
const chartById = new Map();
const chartMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
function chartAnimation() { return { animation: !chartMotion.matches, animationDuration: 400, animationDurationUpdate: 250 }; }
chartMotion.addEventListener("change", () => dashboardCharts.forEach(chart => chart.setOption(chartAnimation())));
let dashboardLoading = false;

function dashboardChart(id) {
    if (!chartById.has(id)) {
        const chart = echarts.init(document.getElementById(id));
        chartById.set(id, chart);
        dashboardCharts.push(chart);
    }
    return chartById.get(id);
}

const yenFormatter = new Intl.NumberFormat(
    "ja-JP",
    {
        style: "currency",
        currency: "JPY",
        maximumFractionDigits: 0
    }
);

const numberFormatter =
    new Intl.NumberFormat("ja-JP");

		function updateClock() {

		    const now = new Date();

		    document.getElementById("today").textContent =
		        new Intl.DateTimeFormat(
		            "ja-JP",
		            {
		                year: "numeric",
		                month: "long",
		                day: "numeric",
		                weekday: "long",
		                hour: "2-digit",
		                minute: "2-digit",
		                second: "2-digit",
		                hour12: false
		            }
		        ).format(now);
		}

		updateClock();

		setInterval(updateClock, 1000);

function showDashboardError(message) {
    $("#errorBox").text(message).show();
    $("#dashboardStatus").text("取得失敗 / 前回表示を保持");
}

function loadDashboard() {
    if (dashboardLoading) return;
    const releaseFeedback = window.PortalFeedback?.busy(document.getElementById("dashboardRefresh"), "取得中") || (() => {});
    dashboardLoading = true;
    $("#dashboardRefresh").prop("disabled", true);
    $.ajax({
        url: "/api/dashboard",
        type: "GET",
        dataType: "json",
        cache: false,
        success: function (data) {
            try {
                $("#errorBox").hide();
                updateSummary(data.summary);
                // グラフライブラリが利用できなくても、数値と一覧は表示する。
                createMonthlyTable(data);
                $("#dashboardUpdated").text(new Date().toLocaleString("ja-JP"));
                $("#dashboardStatus").text("登録済み営業実績を取得済み");
                if (typeof echarts === "undefined") {
                    showDashboardError("EChartsを読み込めませんでした。ネットワーク接続を確認してください。");
                    return;
                }
                createSalesChart(data);
                createDepartmentChart(data);
                createActivityChart(data);
            } catch (error) {
                console.error(error);
                showDashboardError("ダッシュボードデータを取得できませんでした。");
            }
        },
        error: function (xhr) {
            if (xhr.status === 401) {
                window.location.href = "/";
                return;
            }
            showDashboardError(xhr.status === 403
                ? "ダッシュボードを表示する権限がありません。"
                : "ダッシュボードデータを取得できませんでした。");
        },
        complete: function () {
            releaseFeedback();
            dashboardLoading = false;
            $("#dashboardRefresh").prop("disabled", false);
        }
    });
}

function updateSummary(summary) {

    document.getElementById("totalSales")
        .textContent =
        yenFormatter.format(summary.totalSales);

    document.getElementById("totalOrders")
        .textContent =
        numberFormatter.format(summary.totalOrders) + " 件";

    document.getElementById("totalCustomers")
        .textContent =
        numberFormatter.format(summary.totalCustomers) + " 人";

    document.getElementById("employeeCount")
        .textContent =
        numberFormatter.format(summary.employeeCount) + " 人";

    const growth = Number(summary.salesGrowth);

    document.getElementById("salesGrowth")
        .textContent =
        summary.growthAvailable === false ? "比較対象なし" : (growth >= 0 ? "+" : "")
        + growth.toFixed(1)
        + "%";
    $("#salesGrowth").toggleClass("positive", growth >= 0).toggleClass("negative", growth < 0);
}

function getMonthLabels(months) {
    return months.map(function (month) {
        const parts = month.split("-");
        return parts[0] + "/" + parts[1];
    });
}

function createSalesChart(data) {

    const chart = dashboardChart("salesChart");

    const salesMillions =
        data.monthlySales.map(function (value) {
            return Number(value) / 1000000;
        });

    chart.setOption({
        ...chartAnimation(),
        color: ["#2563eb", "#7c9bc3"],

        tooltip: {
            trigger: "axis"
        },

        legend: {
            top: 5,
            right: 5,
            data: ["売上高", "受注件数"]
        },

        grid: {
            top: 55,
            left: 55,
            right: 55,
            bottom: 35
        },

        xAxis: {
            type: "category",
            data: getMonthLabels(data.months),
            axisLine: {
                lineStyle: {
                    color: "#dce3ec"
                }
            }
        },

        yAxis: [
            {
                type: "value",
                name: "百万円",
                splitLine: {
                    lineStyle: {
                        color: "#edf1f5"
                    }
                }
            },
            {
                type: "value",
                name: "件",
                splitLine: {
                    show: false
                }
            }
        ],

        series: [
            {
                name: "売上高",
                type: "line",
                smooth: true,
                data: salesMillions,

                symbolSize: 8,

                lineStyle: {
                    width: 3
                },

                areaStyle: {
                    color: {
                        type: "linear",
                        x: 0,
                        y: 0,
                        x2: 0,
                        y2: 1,
                        colorStops: [
                            {
                                offset: 0,
                                color: "rgba(37,99,235,0.28)"
                            },
                            {
                                offset: 1,
                                color: "rgba(37,99,235,0.02)"
                            }
                        ]
                    }
                }
            },
            {
                name: "受注件数",
                type: "bar",
                yAxisIndex: 1,
                data: data.monthlyOrders,

                barWidth: 17,

                itemStyle: {
                    borderRadius: [5, 5, 0, 0]
                }
            }
        ]
    });

}

function createDepartmentChart(data) {

    const chart = dashboardChart("departmentChart");

    const pieData =
        data.departmentSales.map(function (item) {
            return {
                name: item.name,
                value:
                    Number(item.value) / 1000000
            };
        });

    chart.setOption({
        ...chartAnimation(),
        color: [
            "#2563eb",
            "#0ea5e9",
            "#14b8a6",
            "#8b5cf6"
        ],

        tooltip: {
            trigger: "item",
            formatter:
                "{b}<br>{c} 百万円 ({d}%)"
        },

        legend: {
            bottom: 0,
            left: "center"
        },

        series: [
            {
                name: "部門別売上",
                type: "pie",
                radius: ["46%", "69%"],
                center: ["50%", "44%"],
                data: pieData,

                itemStyle: {
                    borderColor: "#ffffff",
                    borderWidth: 4,
                    borderRadius: 7
                },

                label: {
                    formatter: "{d}%"
                }
            }
        ]
    });

}

function createActivityChart(data) {

    const chart = dashboardChart("activityChart");

    chart.setOption({
        ...chartAnimation(),
        color: ["#2563eb", "#16a36a"],

        tooltip: {
            trigger: "axis"
        },

        legend: {
            top: 5,
            right: 5,
            data: [
                "受注件数",
                "新規顧客数"
            ]
        },

        grid: {
            top: 55,
            left: 55,
            right: 55,
            bottom: 35
        },

        xAxis: {
            type: "category",
            data: getMonthLabels(data.months)
        },

        yAxis: [
            {
                type: "value",
                name: "受注件数",
                splitLine: {
                    lineStyle: {
                        color: "#edf1f5"
                    }
                }
            },
            {
                type: "value",
                name: "顧客数",
                splitLine: {
                    show: false
                }
            }
        ],

        series: [
            {
                name: "受注件数",
                type: "bar",
                data: data.monthlyOrders,
                barWidth: 28,

                itemStyle: {
                    borderRadius: [6, 6, 0, 0]
                }
            },
            {
                name: "新規顧客数",
                type: "line",
                yAxisIndex: 1,
                smooth: true,
                symbolSize: 9,
                data: data.monthlyCustomers,

                lineStyle: {
                    width: 3
                }
            }
        ]
    });

}

function createMonthlyTable(data) {
    const tbody = document.getElementById("monthlyTableBody");
    tbody.replaceChildren();
    if (!data.months.length) {
        const row = document.createElement("tr");
        const cell = document.createElement("td");
        cell.colSpan = 4;
        cell.textContent = "営業実績はまだ登録されていません。";
        row.appendChild(cell);
        tbody.appendChild(row);
        return;
    }
    data.months.forEach(function (month, index) {
        const row = document.createElement("tr");
        const values = [month, yenFormatter.format(data.monthlySales[index]),
            numberFormatter.format(data.monthlyOrders[index]) + " 件",
            numberFormatter.format(data.monthlyCustomers[index]) + " 人"];
        values.forEach(function (value) {
            const cell = document.createElement("td");
            cell.textContent = value;
            row.appendChild(cell);
        });
        tbody.appendChild(row);
    });
}

window.addEventListener("resize", function () {
    dashboardCharts.forEach(function (chart) {
        chart.resize();
    });
});

loadDashboard();
$("#dashboardRefresh").on("click", loadDashboard);
setInterval(function () { if (!document.hidden) loadDashboard(); }, 30000);
document.addEventListener("visibilitychange", function () { if (!document.hidden) loadDashboard(); });
