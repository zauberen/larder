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

// Files chosen in forms with data-upload (the URL to upload to) are sent in pieces no
// larger than the request body limit, so reverse proxies with a small body limit don't
// refuse them; the form is then submitted with only the upload id, in its
// [data-upload-id] field. data-upload-kind tells the server what the file is for.
function bsFormatBytes(n) {
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
    return (i === 0 ? n : n.toFixed(n < 10 ? 1 : 0)) + ' ' + units[i];
}

// The upload status under the file field: a progress bar and a line of text.
function bsUploadStatus(form, { percent, text, error }) {
    const box = form.querySelector('.upload-status');
    if (!box) return;
    const bar = box.querySelector('progress');
    const label = box.querySelector('.upload-status-text');
    box.hidden = false;
    bar.hidden = !!error;
    // Without a value the bar shows as busy (indeterminate).
    if (percent === null) bar.removeAttribute('value'); else if (percent !== undefined) bar.value = percent;
    label.textContent = text;
    label.classList.toggle('error', !!error);
}

// Text of a failed response, or a fallback when it is an HTML page (a proxy's error page).
async function bsResponseMessage(resp, fallback) {
    const type = resp.headers.get('Content-Type') || '';
    const text = type.startsWith('text/plain') ? (await resp.text()).trim() : '';
    return text || `${fallback} (HTTP ${resp.status})`;
}

async function bsUploadFile(form, file) {
    const base = form.dataset.upload;
    const opts = { credentials: 'same-origin', redirect: 'manual' };
    const start = await fetch(base, {
        ...opts, method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ filename: file.name, size: file.size, kind: form.dataset.uploadKind || '' })
    });
    if (start.type === 'opaqueredirect') throw new Error('Your session has expired. Sign in again.');
    if (!start.ok) throw new Error(await bsResponseMessage(start, 'The upload could not be started'));
    // The server picks the piece size (Settings → Server configuration).
    const { id, chunkSize: chunk } = await start.json();

    const began = Date.now();
    let offset = 0;
    let failures = 0;
    const show = () => {
        const percent = file.size ? Math.floor(offset * 100 / file.size) : 100;
        const seconds = (Date.now() - began) / 1000;
        const rate = seconds > 1 ? ` · ${bsFormatBytes(offset / seconds)}/s` : '';
        bsUploadStatus(form, {
            percent,
            text: `Uploading ${file.name}: ${bsFormatBytes(offset)} of ${bsFormatBytes(file.size)} (${percent}%)${rate}`
        });
    };
    while (offset < file.size) {
        show();
        let resp = null;
        try {
            resp = await fetch(`${base}/${encodeURIComponent(id)}?offset=${offset}`, {
                ...opts, method: 'PUT',
                headers: { 'Content-Type': 'application/octet-stream' },
                body: file.slice(offset, offset + chunk)
            });
        } catch (e) {
            // Network error: retried below.
        }
        if (resp && resp.type === 'opaqueredirect') throw new Error('Your session has expired. Sign in again.');
        // 409: the server already has a different amount (a retried piece arrived); resume there.
        if (resp && (resp.ok || resp.status === 409)) {
            const received = Number(await resp.text());
            if (!Number.isFinite(received)) throw new Error('Unexpected response from the server.');
            offset = received;
            failures = 0;
            continue;
        }
        if (resp && resp.status >= 400 && resp.status < 500 && resp.status !== 408 && resp.status !== 429) {
            throw new Error(await bsResponseMessage(resp, 'The upload failed'));
        }
        if (++failures > 5) throw new Error('The upload was interrupted. Check your connection and try again.');
        bsUploadStatus(form, { text: `Connection problem, retrying (${failures} of 5)…` });
        await new Promise(r => setTimeout(r, 1000 * 2 ** failures));
    }
    return id;
}

document.addEventListener('htmx:confirm', evt => {
    const form = evt.detail.elt;
    if (!(form instanceof HTMLFormElement) || !form.dataset.upload) return;
    const input = form.querySelector('[data-upload-file]');
    const file = input && input.files[0];
    const hidden = form.querySelector('[data-upload-id]');
    // Nothing chosen, or this file was already uploaded (e.g. the request failed and is retried).
    if (!file || form.bsUploaded === file) return;
    evt.preventDefault();
    // The button that was pressed: disabling it below makes htmx forget it, so its
    // name and value are sent in a hidden field instead.
    const submitter = (evt.detail.triggeringEvent && evt.detail.triggeringEvent.submitter) || form.querySelector('[type=submit]');
    const button = submitter;
    button.disabled = true;
    input.disabled = true;
    bsUploadFile(form, file).then(id => {
        const previous = hidden.value;
        if (previous && previous !== id) {
            fetch(`${form.dataset.upload}/${encodeURIComponent(previous)}`, { method: 'DELETE', credentials: 'same-origin' });
        }
        hidden.value = id;
        form.bsUploaded = file;
        input.disabled = false;
        if (submitter && submitter.name) {
            let field = form.querySelector('input[data-submitter]');
            if (!field) {
                field = document.createElement('input');
                field.type = 'hidden';
                field.dataset.submitter = '';
                form.append(field);
            }
            field.name = submitter.name;
            field.value = submitter.value;
        }
        bsUploadStatus(form, { percent: null, text: `Uploaded ${file.name}. Saving…` });
        evt.detail.issueRequest(true);
    }, err => {
        button.disabled = false;
        input.disabled = false;
        bsUploadStatus(form, { error: true, text: err.message || 'The upload failed.' });
    });
});
// The hidden submitter field only applies to the request it was made for.
document.addEventListener('htmx:afterRequest', evt => {
    const form = evt.detail.elt;
    if (form instanceof HTMLFormElement && form.dataset.upload) {
        form.querySelectorAll('input[data-submitter]').forEach(f => f.remove());
        form.querySelectorAll('[type=submit]').forEach(b => { b.disabled = false; });
    }
});
document.addEventListener('htmx:sendError', evt => {
    const form = evt.detail.elt;
    if (form instanceof HTMLFormElement && form.dataset.upload) {
        bsUploadStatus(form, { error: true, text: 'Could not reach Larder. Try again.' });
    }
});
document.addEventListener('htmx:responseError', evt => {
    const form = evt.detail.elt;
    if (form instanceof HTMLFormElement && form.dataset.upload) {
        bsUploadStatus(form, { error: true, text: `Saving failed (HTTP ${evt.detail.xhr.status}). Try again.` });
    }
});

// Swapped elements may replace their target, so rescan the document (init is idempotent).
document.addEventListener('htmx:afterSettle', () => bsInit(document));
document.addEventListener('htmx:historyRestore', () => bsInit(document));
document.addEventListener('DOMContentLoaded', () => bsInit(document));
if (document.readyState !== 'loading') bsInit(document);
