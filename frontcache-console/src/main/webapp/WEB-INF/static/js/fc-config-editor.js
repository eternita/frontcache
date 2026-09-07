/*
 * The Edit / Save / Save to all nodes / Cancel behaviour of the Configs screens.
 *
 * Dependency-free, like fc-dashboard.js and fc-edges-status.js. jQuery is on the page (Bootstrap 3
 * needs it) but nothing here does, and a control that switches a page between read-only and editable
 * is not the place to acquire a dependency.
 */
var FcConfigEditor = (function () {
	"use strict";

	var editing = false;
	var fileName = "";

	function activePanel() {
		return document.querySelector(".tab-content .tab-pane.active");
	}

	function panels() {
		return Array.prototype.slice.call(document.querySelectorAll(".tab-content .tab-pane"));
	}

	function textareaOf(panel) {
		return panel ? panel.querySelector(".fc-config-textarea") : null;
	}

	function buttonsOf(panel) {
		return panel ? panel.querySelector(".fc-config-buttons") : null;
	}

	/*
	 * The pristine text, captured at load rather than when Edit is clicked.
	 *
	 * It has to be the load-time value: Cancel restores it, and a second Edit after a Cancel must go
	 * back to what the node sent, not to whatever was on screen when Edit was last pressed.
	 */
	function stashPristine() {
		panels().forEach(function (panel) {
			var textarea = textareaOf(panel);
			if (textarea && null === textarea.getAttribute("data-pristine")) {
				textarea.setAttribute("data-pristine", textarea.value);
			}
		});
	}

	function isDirty() {
		return panels().some(function (panel) {
			var textarea = textareaOf(panel);
			return textarea && textarea.value !== textarea.getAttribute("data-pristine");
		});
	}

	function setEditing(on) {
		editing = on;

		var editButton = document.getElementById("fc-config-edit");
		if (editButton) {
			editButton.style.display = on ? "none" : "";
		}

		panels().forEach(function (panel) {
			var textarea = textareaOf(panel);
			var buttons = buttonsOf(panel);

			if (textarea) {
				// Every tab becomes editable, not only the active one: the panels all live in the DOM,
				// so switching tabs mid-edit must not silently drop what was typed in the one left
				// behind. Each panel's own form posts only its own textarea, to its own edge.
				if (on) {
					textarea.removeAttribute("readonly");
					textarea.classList.add("fc-config-editable");
				} else {
					textarea.setAttribute("readonly", "readonly");
					textarea.classList.remove("fc-config-editable");
				}
			}
			if (buttons) {
				buttons.style.display = on ? "" : "none";
			}
		});

		if (on) {
			var textarea = textareaOf(activePanel());
			if (textarea) {
				textarea.focus();
			}
		}
	}

	function cancel() {
		panels().forEach(function (panel) {
			var textarea = textareaOf(panel);
			if (textarea) {
				textarea.value = textarea.getAttribute("data-pristine");
			}
		});
		setEditing(false);
	}

	/*
	 * SHA-256 of the text the node gave us, so the node can refuse a save that would clobber someone
	 * else's edit.
	 *
	 * crypto.subtle is undefined on a page that is neither https nor localhost - a plain-http console
	 * on a named host, which is a common deployment. In that case the hash is left empty and the node
	 * skips the check, saying so in its response. That is the right trade: computing it server-side
	 * from a posted "original" field would be comparing the node's disk against text the browser sent,
	 * so a stale tab would still win, and refusing to save at all would break those consoles outright.
	 */
	function baseHashFor(textarea) {
		var pristine = textarea.getAttribute("data-pristine");

		if (!window.crypto || !window.crypto.subtle || !window.TextEncoder) {
			return Promise.resolve("");
		}

		// The node normalizes CRLF to LF before hashing, so hash the same shape here - a textarea
		// rendered from the server holds LF, but a value that has been through a form round trip may
		// not, and a mismatch here would read as a phantom conflict.
		var normalized = pristine.replace(/\r\n/g, "\n").replace(/\r/g, "\n");

		return window.crypto.subtle.digest("SHA-256", new TextEncoder().encode(normalized))
			.then(function (buffer) {
				return Array.prototype.map.call(new Uint8Array(buffer), function (b) {
					return ("0" + b.toString(16)).slice(-2);
				}).join("");
			})
			.catch(function () {
				return "";
			});
	}

	function submit(form, scope) {
		var textarea = form.querySelector(".fc-config-textarea");
		var panel = form.closest(".tab-pane");
		var edgeName = panel ? panel.getAttribute("data-edge-name") : "";

		if ("all" === scope) {
			var count = panels().filter(function (p) {
				return null !== textareaOf(p);
			}).length;

			// Config files legitimately differ between nodes - this project's own e2e fleet keeps the
			// filter node on pre-2.9.0 resilience keys and a bare-keyword bots.conf on purpose - so
			// flattening them all is a thing to be asked about, not a second Save button.
			if (!window.confirm("Overwrite conf/" + fileName + " on ALL " + count
					+ " nodes with this content?\n\nNodes whose file differs today will lose that difference.")) {
				return;
			}
		}

		form.querySelector("input[name=scope]").value = scope;

		baseHashFor(textarea).then(function (hash) {
			form.querySelector("input[name=baseHash]").value = hash;
			// Past the confirm and the hash: from here the page is leaving, so the unsaved-changes
			// guard must not fire.
			editing = false;
			form.submit();
		});
	}

	function attach(editButton, configFileName) {
		fileName = configFileName || "";
		stashPristine();

		if (editButton) {
			editButton.addEventListener("click", function () {
				setEditing(true);
			});
		}

		document.addEventListener("click", function (event) {
			var target = event.target.closest ? event.target.closest("button") : null;
			if (!target) {
				return;
			}

			if (target.classList.contains("fc-config-cancel")) {
				event.preventDefault();
				cancel();
				return;
			}

			var form = target.closest(".fc-config-form");
			if (!form) {
				return;
			}

			if (target.classList.contains("fc-config-save")) {
				event.preventDefault();
				submit(form, "node");
			} else if (target.classList.contains("fc-config-save-all")) {
				event.preventDefault();
				submit(form, "all");
			}
		});

		window.addEventListener("beforeunload", function (event) {
			if (editing && isDirty()) {
				// Switching tabs keeps edits (the panels are all in the DOM); navigating away does not.
				event.preventDefault();
				event.returnValue = "";
				return "";
			}
		});
	}

	return { attach: attach };
})();
