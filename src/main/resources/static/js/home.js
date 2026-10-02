const dashboardCharts = [];

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

async function loadDashboard() {
    try {
        const response =
            await fetch("/api/dashboard", {
                headers: {
                    "Accept": "application/json"
                }
            });

        const contentType =
            response.headers.get("content-type") || "";

        if (!contentType.includes("application/json")) {
            window.location.href = "/";
            return;
        }

        if (!response.ok) {
            throw new Error("API request failed");
        }

        const data = await response.json();

			updateSummary(data.summary);

			// 最初にテーブルを表示する
			createMonthlyTable(data);

			// EChartsの読み込み完了を確認する
			if (typeof echarts === "undefined") {

			    const errorBox =
			        document.getElementById("errorBox");

			    errorBox.textContent =
			        "EChartsを読み込めませんでした。ネットワーク接続を確認してください。";

			    errorBox.style.display = "block";

			    return;
			}

			// 再绘制图表
			createSalesChart(data);
			createDepartmentChart(data);
			createActivityChart(data);

    } catch (error) {
        console.error(error);

        const errorBox =
            document.getElementById("errorBox");

        errorBox.textContent =
            "ダッシュボードデータを取得できませんでした。";

        errorBox.style.display = "block";
    }
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
        (growth >= 0 ? "+" : "")
        + growth.toFixed(1)
        + "%";
}

function getMonthLabels(months) {
    return months.map(function (month) {
        const parts = month.split("-");
        return Number(parts[1]) + "月";
    });
}

function createSalesChart(data) {

    const chart =
        echarts.init(
            document.getElementById("salesChart")
        );

    const salesMillions =
        data.monthlySales.map(function (value) {
            return Number(value) / 1000000;
        });

    chart.setOption({
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

    dashboardCharts.push(chart);
}

function createDepartmentChart(data) {

    const chart =
        echarts.init(
            document.getElementById(
                "departmentChart"
            )
        );

    const pieData =
        data.departmentSales.map(function (item) {
            return {
                name: item.name,
                value:
                    Number(item.value) / 1000000
            };
        });

    chart.setOption({
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

    dashboardCharts.push(chart);
}

function createActivityChart(data) {

    const chart =
        echarts.init(
            document.getElementById(
                "activityChart"
            )
        );

    chart.setOption({
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

    dashboardCharts.push(chart);
}

function createMonthlyTable(data) {

    const tbody =
        document.getElementById(
            "monthlyTableBody"
        );

    tbody.innerHTML = "";

    data.months.forEach(function (month, index) {

        const row =
            document.createElement("tr");

        row.innerHTML =
            "<td>" + month + "</td>"
            + "<td>"
            + yenFormatter.format(
                data.monthlySales[index]
            )
            + "</td>"
            + "<td>"
            + numberFormatter.format(
                data.monthlyOrders[index]
            )
            + " 件</td>"
            + "<td>"
            + numberFormatter.format(
                data.monthlyCustomers[index]
            )
            + " 人</td>";

        tbody.appendChild(row);
    });
}

window.addEventListener("resize", function () {
    dashboardCharts.forEach(function (chart) {
        chart.resize();
    });
});

loadDashboard();
