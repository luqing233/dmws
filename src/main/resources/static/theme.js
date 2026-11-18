"use strict";

(() => {
    const storageKey = "fishing-theme";
    function apply(theme) {
        document.documentElement.dataset.theme = theme === "light" ? "light" : "dark";
        const button = document.getElementById("theme-toggle");
        if (button) {
            const action = document.documentElement.dataset.theme === "dark" ? "浅色模式" : "暗色模式";
            button.textContent = action;
            button.setAttribute("aria-label", `切换到${action}`);
        }
    }
    let saved = "dark";
    try {
        saved = localStorage.getItem(storageKey) || "dark";
    } catch {
        /* Default remains dark if storage is unavailable. */
    }
    apply(saved);
    document.addEventListener("DOMContentLoaded", () => {
        apply(document.documentElement.dataset.theme);
        document.getElementById("theme-toggle").addEventListener("click", () => {
            const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
            apply(next);
            try {
                localStorage.setItem(storageKey, next);
            } catch {
                /* Switching still works in this page. */
            }
        });
    });
    window.addEventListener("storage", (event) => {
        if (event.key === storageKey) apply(event.newValue || "dark");
    });
})();
