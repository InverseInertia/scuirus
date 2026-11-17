// End-to-end test in Chromium with a phone viewport and a fake camera that shows a barcode.
// Run: node tests/e2e/phone.test.mjs   (from the repo root, with the venv's python on PATH or in .venv)

import { spawn, execFileSync } from "node:child_process";
import { mkdtempSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium, devices } = require("playwright");

const root = resolve(import.meta.dirname, "../..");
const python = existsSync(join(root, ".venv/bin/python")) ? join(root, ".venv/bin/python") : "python3";
const work = mkdtempSync(join(tmpdir(), "scuirus-e2e-"));
const shots = process.env.SHOTS || join(work, "shots");
const port = 8765 + Math.floor(Math.random() * 1000);
const base = `http://127.0.0.1:${port}`;

execFileSync(python, ["-m", "tests.e2e.make_media", join(work, "media")], { cwd: root });

const server = spawn(python, ["-m", "tests.e2e.serve", join(work, "data"), String(port)], { cwd: root, stdio: "inherit" });
const stop = () => server.kill();
process.on("exit", stop);

async function waitForServer() {
  for (let i = 0; i < 100; i++) {
    try { if ((await fetch(`${base}/api/status`)).ok) return; } catch {}
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error("server did not start");
}

const step = name => console.log(`- ${name}`);

let browser;
try {
  await waitForServer();
  browser = await chromium.launch({
    args: [
      "--use-fake-ui-for-media-stream",
      "--use-fake-device-for-media-stream",
      `--use-file-for-fake-video-capture=${join(work, "media/camera.y4m")}`,
    ],
  });
  const context = await browser.newContext({ ...devices["Pixel 7"], permissions: ["camera"] });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", e => errors.push(e.message));
  page.on("console", m => m.type() === "error" && errors.push(m.text()));
  const shot = name => page.screenshot({ path: join(shots, `${name}.png`) });

  await page.goto(base);
  await page.getByText("Nothing in stock yet").waitFor();
  step("empty stock page loads");

  // Scan an unknown barcode in Add mode: the product form opens prefilled from the lookup.
  await page.getByRole("button", { name: "Scan" }).click();
  await page.getByRole("radio", { name: "Add" }).click();
  await page.getByRole("button", { name: "Start camera" }).click();
  const dialog = page.locator("#product-dialog");
  await dialog.waitFor({ state: "visible", timeout: 10000 });
  assert.equal(await dialog.locator("input[name=barcode]").inputValue(), "036000291452");
  assert.equal(await dialog.locator("input[name=name]").inputValue(), "Acme Whole Milk");
  await dialog.locator("input[name=unit]").fill("gallon");
  await dialog.locator("input[name=default_use]").fill("1/4");
  await dialog.locator("select[name=location]").selectOption("fridge");
  await shot("1-new-product");
  await dialog.getByRole("button", { name: "Save" }).click();
  await page.locator("#scan-log").getByText("Added 1 Acme Whole Milk").waitFor();
  step("camera scan of unknown barcode -> new product -> added");

  // The code stays in view after the camera restarts; it must not be added a second time.
  await page.waitForTimeout(4000);
  assert.equal(await page.locator("#scan-log li").count(), 1, "barcode held in view was added twice");
  step("holding the code in view does not add it again");

  // Switch to Use: move the code out of view by stopping, then scan again.
  await page.getByRole("button", { name: "Stop camera" }).click();
  await page.getByRole("radio", { name: "Use up" }).click();
  await page.getByRole("button", { name: "Start camera" }).click();
  await page.locator("#scan-log").getByText("Used 1/4 Acme Whole Milk · 3/4 gallon left").waitFor({ timeout: 10000 });
  await shot("2-scan-log");
  await page.getByRole("button", { name: "Stop camera" }).click();
  step("camera scan in Use mode removes the default amount");

  // Typed barcode works too.
  await page.locator("#manual-code input").fill("036000291452");
  await page.locator("#manual-code button").click();
  await page.locator("#scan-log").getByText("1/2 gallon left").waitFor();
  step("typed barcode");

  // Import a receipt photo through the file picker.
  await page.getByRole("button", { name: "Receipts" }).click();
  await page.locator("#receipt-files").setInputFiles(join(work, "media/receipt.jpg"));
  await page.getByText("Reading…").waitFor();
  await shot("3-receipt-processing");
  await page.getByRole("button", { name: "Review" }).click({ timeout: 10000 });
  const sheet = page.locator("#receipt-dialog");
  await sheet.getByText("Organic whole milk").first().waitFor();
  // The paper bag is not food and starts unticked; three items are selected.
  const addBtn = sheet.getByRole("button", { name: /Add 3 items to stock/ });
  await addBtn.waitFor();
  // Milk line is matched to the product created by scanning.
  const milkSelect = sheet.getByRole("combobox", { name: "Product for Organic whole milk" });
  assert.equal(await milkSelect.locator("option:checked").textContent(), "Acme Whole Milk");
  await shot("4-receipt-review");
  await addBtn.click();
  await page.getByText("Added to stock").waitFor();
  step("receipt import -> review -> add");

  // Stock now shows all items; the milk card has two lots.
  await page.getByRole("button", { name: "Stock" }).click();
  for (const name of ["Acme Whole Milk", "Bananas", "Boneless chicken breast"]) {
    await page.locator(".card .name", { hasText: name }).waitFor();
  }
  const milk = page.locator(".card", { hasText: "Acme Whole Milk" });
  assert.match(await milk.locator(".sub").first().textContent(), /1 1\/2 gallon/);
  await milk.locator(".name").click();
  await shot("5-stock-expanded");
  await milk.getByRole("button", { name: "Toss" }).first().click();
  await page.getByText("Tossed Acme Whole Milk").waitFor();
  step("stock list, expand, toss a lot");

  await page.getByRole("button", { name: "History" }).click();
  await page.locator("#history-list li").first().waitFor();
  assert.ok((await page.locator("#history-list li").count()) >= 7);
  await shot("6-history");
  step("history");

  // Dark mode renders too.
  await page.emulateMedia({ colorScheme: "dark" });
  await page.getByRole("button", { name: "Stock" }).click();
  await shot("7-dark");

  assert.deepEqual(errors, [], `browser errors: ${errors.join("\n")}`);
  console.log(`e2e passed. Screenshots in ${shots}`);
} finally {
  await browser?.close();
  stop();
}
