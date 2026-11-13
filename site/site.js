"use strict";

const copyButton = document.getElementById("copy-url");
if (navigator.clipboard && copyButton) {
  copyButton.hidden = false;
  copyButton.addEventListener("click", async () => {
    const status = document.getElementById("copy-status");
    try {
      await navigator.clipboard.writeText(document.getElementById("update-url").href);
      status.textContent = "コピーしました / Copied";
    } catch {
      status.textContent = "上のURLを選択してコピーしてください / Select the URL to copy";
    }
  });
}

fetch("build.json")
  .then(response => response.ok ? response.json() : Promise.reject())
  .then(build => {
    const info = document.getElementById("build-info");
    info.textContent = `Build ${build.plugin_version}`;
    if (/^[a-f0-9]{40}$/.test(build.commit || "")) {
      const link = document.createElement("a");
      link.href = `https://github.com/vipt/doma-result-generator/commit/${build.commit}`;
      link.textContent = build.commit.slice(0, 7);
      info.append(" · ", link);
    }
  })
  .catch(() => {});
