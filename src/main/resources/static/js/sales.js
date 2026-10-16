$(document).ready(function () {
    const form = $("#salesForm");
    const department = $("#salesDepartment");
    let loading = false;
    let saved = false;

    function resetForm() {
        form[0].reset();
        form.find('[name="id"], [name="version"]').val("");
        $("#salesMonth").prop("readOnly", false);
        department.prop("disabled", false);
        form.find('[data-edit-department]').remove();
        department.find('[data-edit-option]').remove();
        $("#salesFormTitle").text("月次実績を登録");
    }

    $("#salesReset").on("click", function () {
        if (!loading && !form.is('[aria-busy="true"]')) resetForm();
    });
    form.on("ajax:success", function () { saved = true; });
    form.on("ajax:idle", function () { if (saved) { saved = false; resetForm(); } });

    $(document).on("click", ".sales-edit", async function () {
        if (loading || form.is('[aria-busy="true"]')) return;
        loading = true;
        try {
            if (!await PortalDialog.confirm("入力中の内容を破棄し、選択した実績を編集しますか？", { confirmLabel: "編集する" })) {
                loading = false;
                return;
            }
        } catch {
            loading = false;
            return;
        }
        if (form.is('[aria-busy="true"]')) { loading = false; return; }
        const controls = form.find("input, select, textarea, button").toArray().map(function (field) { return [field, field.disabled]; });
        controls.forEach(function (item) { item[0].disabled = true; });
        let editing = false;
        $.ajax({
            url: "/sales/" + $(this).data("id"), type: "GET", dataType: "json", cache: false,
            success: function (data) {
                resetForm();
                if (department.is("select") && !department.find("option").toArray().some(function (option) { return option.value === data.department; })) {
                    $("<option>", { value: data.department, text: data.department + "（履歴）", "data-edit-option": "" }).appendTo(department);
                }
                ["id", "version", "salesMonth", "department", "salesAmount", "orderCount", "customerCount", "notes"].forEach(function (name) {
                    form.find('[name="' + name + '"]').val(data[name]);
                });
                $("#salesMonth").prop("readOnly", true);
                // disabledのselectは送信されないため、同じ部署名をhiddenで送信する。
                if (department.is("select")) {
                    department.prop("disabled", true);
                    $("<input>", { type: "hidden", name: "department", value: data.department, "data-edit-department": "" }).appendTo(form);
                }
                $("#salesFormTitle").text(data.salesMonth + " / " + data.department + " の実績を編集");
                editing = true;
                document.getElementById("sales-entry").scrollIntoView({ behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "start" });
            },
            error: function (xhr) {
                if (xhr.status === 401) { window.location.href = "/"; return; }
                PortalDialog.info(xhr.responseJSON?.message || "営業実績を取得できませんでした。", "データ取得エラー");
            },
            complete: function () {
                controls.forEach(function (item) { item[0].disabled = item[1]; });
                if (editing && department.is("select")) department.prop("disabled", true);
                loading = false;
            }
        });
    });
});
