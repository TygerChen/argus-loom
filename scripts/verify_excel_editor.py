"""Browser verification for the Excel editor, DOM-based only (no screenshots).

Run:
    python scripts/verify_excel_editor.py

Requires the frontend dev server on 8081 and backend on 10001.
"""

import json
import random
import time

from playwright.sync_api import sync_playwright

FRONTEND_BASE = "http://localhost:8081"
ADMIN_USER = "admin"
ADMIN_PASS = "admin123"


def build_sample_xlsx(path):
    """Create a small xlsx with exceljs. Uses frontend dependency at runtime."""
    import subprocess
    code = r"""
const ExcelJS = require("exceljs");
(async () => {
  const wb = new ExcelJS.Workbook();
  const ws = wb.addWorksheet("验证表");
  ws.columns = [
    { header: "产品", key: "name", width: 18 },
    { header: "数量", key: "qty", width: 12 },
    { header: "单价", key: "price", width: 12 },
  ];
  ws.addRows([
    { name: "服务器", qty: 3, price: 12000 },
    { name: "数据库许可", qty: 2, price: 8800 },
  ]);
  ws.getCell("A1").font = { bold: true };
  ws.getCell("A1").fill = { type: "pattern", pattern: "solid", fgColor: { argb: "FFDDEEFF" } };
  await wb.xlsx.writeFile(process.argv[1]);
  console.log("XLSX_OK");
})().catch((e) => { console.error(e); process.exit(1); });
"""
    # Build via node from frontend's node_modules.
    proc = subprocess.run(
        ["node", "-e", code, path],
        cwd=r"F:\ChatGPT\argus-loom\frontend",
        capture_output=True,
        text=True,
    )
    print("BUILD_XLSX", proc.returncode, proc.stdout.strip(), proc.stderr.strip()[:300])


def dims(page):
    return page.evaluate(
        """() => {
          const el = document.getElementById('univer-container');
          const rect = el ? el.getBoundingClientRect() : null;
          const canvases = [...document.querySelectorAll('#univer-container canvas')].map(
            (c) => ({ w: c.width, h: c.height, cls: c.className.slice(0, 40) })
          );
          const text = document.getElementById('univer-container')?.innerText?.slice(0, 120);
          return { rect: rect && { x: rect.x, y: rect.y, w: rect.width, h: rect.height }, canvases, text };
        }"""
    )


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, channel="chrome")
        page = browser.new_page(viewport={"width": 1440, "height": 900})
        page.on("console", lambda msg: print("BROWSER_CONSOLE", msg.type, msg.text[:220]))
        page.on("pageerror", lambda err: print("PAGE_ERROR", str(err)[:300]))
        page.on(
            "response",
            lambda resp: print(
                "HTTP", resp.status, resp.request.method, resp.url.replace(FRONTEND_BASE, "")
            )
            if resp.status >= 400 and "/api/" in resp.url
            else None,
        )

        page.goto(FRONTEND_BASE + "/login", wait_until="domcontentloaded")
        page.get_by_placeholder("用户名或邮箱").fill(ADMIN_USER)
        page.get_by_placeholder("输入密码").fill(ADMIN_PASS)
        page.get_by_role("button", name="登录").click()
        page.wait_for_timeout(2500)
        print("AFTER_LOGIN_URL", page.url)
        print("AFTER_LOGIN_BODY", page.locator("body").inner_text()[:600].replace("\n", " | "))
        print(
            "AUTH_STORAGE",
            page.evaluate(
                "() => ({ keys: Object.keys(localStorage), sample: Object.fromEntries(Object.entries(localStorage).filter(([k]) => k.toLowerCase().includes('token'))) })"
            ),
        )
        # Stay inside the SPA so the in-memory auth store survives (token is not persisted).
        page.evaluate(
            """() => {
              window.history.pushState({}, '', '/app/excel');
              window.dispatchEvent(new PopStateEvent('popstate'));
            }"""
        )
        page.wait_for_selector(".document-table tbody tr, .document-table .el-table__empty-text", timeout=20000)
        page.wait_for_timeout(1200)

        rows = page.locator(".document-table tbody tr").count()
        print("DOC_ROWS", rows)
        if rows == 0:
            xlsx_path = r"F:\ChatGPT\argus-loom\scripts\_verify_sample.xlsx"
            build_sample_xlsx(xlsx_path)
            page.set_input_files("input.file-input", xlsx_path)
            page.wait_for_selector("#univer-container canvas", timeout=30000)
            print("UPLOAD_AFTER_CANVAS", True)
        else:
            row = page.locator(".document-table tbody tr").nth(0)
            print("FIRST_ROW", row.inner_text()[:160])
            row.locator("button:has-text('编辑')").click()

        page.wait_for_selector("#univer-container canvas", timeout=30000)
        time.sleep(1.2)
        info = dims(page)
        print("EDITOR_INFO", json.dumps(info, ensure_ascii=False))

        body_text = page.locator("body").inner_text()
        for label in ["高级功能", "撤销", "重做", "历史", "版本", "保存", "导出 XLSX", "返回列表"]:
            print("HAS_LABEL", label, label in body_text)

        # Open advanced menu and enumerate items
        page.get_by_role("button", name="高级功能").click()
        page.wait_for_timeout(500)
        menu = page.locator(".el-dropdown-menu").last
        items = menu.locator(".el-dropdown-menu__item, .el-dropdown-menu .el-dropdown-menu__item").all_inner_texts()
        print("ADVANCED_MENU", json.dumps(items, ensure_ascii=False))
        page.keyboard.press("Escape")
        page.wait_for_timeout(400)
        print("COLLAB_LABEL", page.locator(".collab-label").inner_text())

        # Drive a real edit through the dev-exposed Univer API, then save via UI.
        token = "校验" + str(random.randint(1000, 9999))
        api_result = page.evaluate(
            """(token) => {
              const api = window.__argusUniverAPI;
              const workbook = api.getActiveWorkbook();
              const sheet = workbook.getActiveSheet();
              if (!api || !sheet) return { ok: false, reason: 'api/sheet missing' };
              const executed = api.executeCommand('sheet.command.set-range-values', {
                subUnitId: sheet.getSheetId(),
                range: { startRow: 0, startColumn: 5, endRow: 0, endColumn: 5 },
                value: { v: token }
              });
              if (executed === false) return { ok: false, reason: 'command rejected' };
              const cell = sheet.getRange(0, 5).getValue();
              return { ok: true, cell };
            }""",
            token,
        )
        print("UNIVER_EDIT", json.dumps(api_result, ensure_ascii=False))
        page.wait_for_timeout(700)
        print("DIRTY_TAG", "有未保存修改" in page.locator("body").inner_text())

        page.get_by_role("button", name="保存", exact=True).click()
        page.wait_for_timeout(2500)
        tail = page.locator("body").inner_text()
        print(
            "SAVE_RESULT",
            "保存成功" in tail,
            "已保存" in tail,
            "保存失败" in tail,
            "DIRTY_AFTER_SAVE",
            "有未保存修改" in tail,
        )

        browser.close()


if __name__ == "__main__":
    main()
