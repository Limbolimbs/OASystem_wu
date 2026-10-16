/* 詳細・編集もjQuery AJAXを使用し、一覧を全画面再読込しない。 */
$(document).ready(function () {
    const form = document.getElementById("noticeForm");
    const dialog = document.getElementById("noticeDetail");
    let resetAfterSave = false;
    let detailRequest = null;
    let detailSequence = 0;
    let confirming = false;
    const headers = { "X-Requested-With": "XMLHttpRequest" };
    function cancelDetail() {
        detailSequence++;
        if (detailRequest) { detailRequest.abort(); detailRequest = null; }
    }
    function error(xhr) {
        if (xhr.status === 401) { window.location.assign("/"); return; }
        $("#noticeMessage").text(xhr.responseJSON?.message || "お知らせを取得できませんでした。一覧を再取得してください。").prop("hidden", false);
    }
    function clearEditor() {
        if (!form) return;
        cancelDetail();
        form.reset(); form.elements.id.value = ""; form.elements.version.value = "";
        $("#noticeFormTitle").text("お知らせを作成");
    }
    async function confirmDiscard(message) {
        if (confirming) return false;
        confirming = true;
        try { return await PortalDialog.confirm(message, { confirmLabel: "入力を破棄する", danger: true }); }
        finally { confirming = false; }
    }
    $("#noticeReset").on("click", async function () {
        if (!form || confirming || form.getAttribute("aria-busy") === "true") return;
        if ((form.elements.content.value || form.elements.title.value) && !await confirmDiscard("入力中の内容を破棄して新規作成に戻りますか？")) return;
        if (form.getAttribute("aria-busy") !== "true") clearEditor();
    });
    if (form) {
        form.addEventListener("submit", cancelDetail);
        form.addEventListener("ajax:success", () => { resetAfterSave = true; });
        form.addEventListener("ajax:idle", () => { if (resetAfterSave) { resetAfterSave = false; clearEditor(); } });
    }
    function load(id, callback) {
        if (detailRequest) detailRequest.abort();
        const sequence = ++detailSequence;
        $("#noticeMessage").prop("hidden", true);
        detailRequest = $.ajax({ url: "/notices/" + encodeURIComponent(id), type: "GET", dataType: "json", headers,
            success: function (value) { if (sequence === detailSequence) callback(value, sequence); },
            error: function (xhr, state) { if (state !== "abort") error(xhr); },
            complete: function () { if (sequence === detailSequence) detailRequest = null; }
        });
    }
    $(document).on("click", ".notice-edit", async function () {
        if (!form || confirming || form.getAttribute("aria-busy") === "true") return;
        if ((form.elements.content.value || form.elements.title.value) && !await confirmDiscard("入力中の内容を破棄して、このお知らせを編集しますか？")) return;
        if (form.getAttribute("aria-busy") === "true") return;
        load(this.dataset.id, function (value) {
            if (form.getAttribute("aria-busy") === "true") return;
            if (value.deleted) { $("#noticeMessage").text("無効なお知らせは先に復元してください。").prop("hidden", false); return; }
            ["id", "version", "title", "content", "category", "priority", "status"].forEach(name => { form.elements[name].value = value[name] ?? ""; });
            $("#noticeFormTitle").text("お知らせを編集 #" + value.id);
            document.getElementById("notice-editor").scrollIntoView({ behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "start" });
            form.elements.title.focus({ preventScroll: true });
        });
    });
    $(document).on("click", ".notice-view", function () {
        load(this.dataset.id, function (value, sequence) {
            $("#noticeDetailTitle").text(value.title); $("#noticeDetailBody").text(value.content);
            const date = String(value.updatedAt || "").replace("T", " ").slice(0, 19);
            $("#noticeDetailMeta").text(value.category + " ／ " + (value.priority === "IMPORTANT" ? "重要" : "通常") + " ／ 作成者：" + value.author + " ／ 更新：" + date);
            $("#noticeReadMessage").text("");
            if (!dialog.open) dialog.showModal();
            if (value.status !== "PUBLISHED" || value.deleted) return;
            $.ajax({ url: "/notices/" + encodeURIComponent(value.id) + "/read", type: "POST", data: { version: value.version }, dataType: "json",
                headers: headers,
                success: function (result) {
                    if (!result.success) { $("#noticeReadMessage").text(result.message); return; }
                    if (sequence === detailSequence) $("#noticeReadMessage").text("既読にしました。内容が更新された場合は未読に戻ります。");
                    const link = document.getElementById("noticeRefresh");
                    const region = document.getElementById("notice-data");
                    if (link && region?.dataset.ajaxUrl) link.href = region.dataset.ajaxUrl;
                    link?.click();
                },
                error: function (xhr) {
                    if (xhr.status === 401) window.location.assign("/");
                    else if (sequence === detailSequence) $("#noticeReadMessage").text(xhr.responseJSON?.message || "既読状態を更新できませんでした。最新内容を確認してください。");
                }
            });
        });
    });
    $("#noticeClose").on("click", () => { if (window.PortalFeedback) PortalFeedback.closeDialog(dialog); else dialog.close(); });
    dialog?.addEventListener("cancel", event => {
        if (!window.PortalFeedback) return;
        event.preventDefault(); PortalFeedback.closeDialog(dialog);
    });
    // 検索・ページ送り後の再取得も、その時点の表示条件を使う。
    document.addEventListener("click", function (event) {
        const link = event.target.closest("#noticeRefresh");
        const region = document.getElementById("notice-data");
        if (link && region?.dataset.ajaxUrl) link.href = region.dataset.ajaxUrl;
    }, true);
});
