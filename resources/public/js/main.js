/*
 * Larder - a household recipe, pantry and grocery list manager.
 * Copyright (C) 2025-2026 zauberen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

// Larder front-end helpers. Everything is (re)initialised after htmx swaps.

// On narrow screens the tab bar scrolls; keep the selected tab visible.
function bsRevealActiveTab(root) {
    root.querySelectorAll('.tabs').forEach(tabs => {
        const tab = tabs.querySelector('.tab.active');
        if (tab) tabs.scrollLeft = tab.offsetLeft - (tabs.clientWidth - tab.offsetWidth) / 2;
    });
}

// The sidebar highlight follows the main element's data-section, since in-app links
// swap only #main and leave the sidebar as it was.
function bsMarkSection() {
    const main = document.getElementById('main');
    if (!main) return;
    const section = main.dataset.section || '';
    document.querySelectorAll('.sidebar .nav-button[data-section]').forEach(a => {
        a.classList.toggle('active', a.dataset.section === section);
    });
}

function bsInit(root) {
    bsRevealActiveTab(root);
    bsMarkSection();
}

// Copy buttons (event delegation, so swapped-in buttons work too).
document.addEventListener('click', evt => {
    const btn = evt.target.closest('[data-copy]');
    if (!btn) return;
    const text = btn.dataset.copy;
    const done = () => {
        const old = btn.textContent;
        btn.textContent = 'Copied!';
        setTimeout(() => { btn.textContent = old; }, 1500);
    };
    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(done);
    } else {
        const ta = document.createElement('textarea');
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        ta.remove();
        done();
    }
});

let bsDialogOpen = false;

// Themed replacements for confirm() and prompt(). Resolves to true (confirmed), the
// typed text (prompt) or null (cancelled).
function bsDialog({ title, message, confirmLabel, danger, prompt }) {
    return new Promise(resolve => {
        const dialog = document.createElement('dialog');
        dialog.className = 'bs-dialog';
        const form = document.createElement('form');
        form.method = 'dialog';
        const h = document.createElement('h2');
        h.textContent = title;
        const p = document.createElement('p');
        p.textContent = message;
        form.append(h, p);
        let input = null;
        if (prompt) {
            input = document.createElement('input');
            input.className = 'form-input';
            input.type = prompt.type || 'text';
            input.autocomplete = 'off';
            form.append(input);
        }
        const error = document.createElement('p');
        error.className = 'bs-dialog-error';
        error.hidden = true;
        form.append(error);
        const actions = document.createElement('div');
        actions.className = 'form-actions';
        const cancel = document.createElement('button');
        cancel.type = 'button';
        cancel.className = 'btn btn-secondary';
        cancel.textContent = 'Cancel';
        const ok = document.createElement('button');
        ok.type = 'submit';
        ok.className = 'btn ' + (danger ? 'btn-danger' : 'btn-primary');
        ok.textContent = confirmLabel || 'Confirm';
        actions.append(cancel, ok);
        form.append(actions);
        dialog.append(form);

        // Typing a value to confirm (e.g. a server's id) keeps the button disabled until it matches.
        if (input && prompt.match) {
            ok.disabled = true;
            input.addEventListener('input', () => { ok.disabled = input.value !== prompt.match; });
        }

        let result = null;
        const close = value => { result = value; dialog.close(); };
        cancel.addEventListener('click', () => close(null));
        form.addEventListener('submit', evt => {
            evt.preventDefault();
            const min = prompt && Number(prompt.minLength);
            if (min && input.value.length < min) {
                error.textContent = `Must be at least ${min} characters.`;
                error.hidden = false;
                input.focus();
                return;
            }
            close(input ? input.value : true);
        });
        // Clicks on the backdrop land on the dialog element itself.
        dialog.addEventListener('click', evt => { if (evt.target === dialog) close(null); });
        dialog.addEventListener('close', () => { bsDialogOpen = false; dialog.remove(); resolve(result); });

        bsDialogOpen = true;
        document.body.append(dialog);
        dialog.showModal();
        (input || ok).focus();
    });
}

// hx-confirm and data-prompt use bsDialog. The prompt answer goes in the HX-Prompt
// header, like htmx's own hx-prompt. Unlike confirm(), the dialog doesn't block the
// page, so other requests (polling) are skipped while it is open; otherwise a poll
// could swap out the button being confirmed.
document.addEventListener('htmx:confirm', evt => {
    if (bsDialogOpen) { evt.preventDefault(); return; }
    const elt = evt.detail.elt;
    const prompt = elt.dataset.prompt;
    const question = evt.detail.question;
    if (!question && !prompt) return;
    evt.preventDefault();
    // On a form, label the dialog after its submit button, not the whole form's text
    // (which would include every <option> of its selects).
    const button = elt.tagName === 'FORM'
        ? (evt.detail.triggeringEvent && evt.detail.triggeringEvent.submitter) || elt.querySelector('[type=submit]')
        : elt;
    const label = button ? button.textContent.trim() : '';
    bsDialog({
        title: label || 'Are you sure?',
        message: prompt || question,
        confirmLabel: label,
        danger: !!button && button.matches('.btn-danger, .shutdown-button'),
        prompt: prompt && {
            type: elt.dataset.promptType,
            match: elt.dataset.promptMatch,
            minLength: elt.dataset.promptMinLength
        }
    }).then(answer => {
        if (answer === null) return;
        if (prompt) elt.bsPromptAnswer = answer;
        evt.detail.issueRequest(true);
    });
});
document.addEventListener('htmx:configRequest', evt => {
    const elt = evt.detail.elt;
    if (elt.bsPromptAnswer === undefined) return;
    evt.detail.headers['HX-Prompt'] = elt.bsPromptAnswer;
    delete elt.bsPromptAnswer;
});

// Swapped elements may replace their target, so rescan the document (init is idempotent).
document.addEventListener('htmx:afterSettle', () => bsInit(document));
document.addEventListener('htmx:historyRestore', () => bsInit(document));
document.addEventListener('DOMContentLoaded', () => bsInit(document));
if (document.readyState !== 'loading') bsInit(document);
