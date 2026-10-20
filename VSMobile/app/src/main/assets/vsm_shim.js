(function (g) {
  var bridge = g.__bridge;
  var isEngine = g.__vsmEngine === true;

  function invoke(method, args) {
    var raw = bridge.call(method, JSON.stringify(args === undefined ? [] : args));
    var res = JSON.parse(raw);
    if (res.e) {
      var err = new Error(res.m);
      err.code = res.e;
      throw err;
    }
    return res.r;
  }

  var listeners = {};
  var commands = {};

  function addListener(event, fn, once) {
    if (typeof fn !== 'function') throw new Error('Обработчик должен быть функцией');
    (listeners[event] = listeners[event] || []).push({ fn: fn, once: once });
  }

  function removeListener(event, fn) {
    listeners[event] = (listeners[event] || []).filter(function (l) { return l.fn !== fn; });
  }

  function report(where, e) {
    try {
      invoke('log', [where + ': ' + (e && e.message ? e.message : String(e))]);
    } catch (ignored) {}
  }

  var vsm = {
    log: function () {
      invoke('log', [Array.prototype.slice.call(arguments).map(String).join(' ')]);
    },
    app: {
      info: function () { return invoke('app.info'); }
    },
    editor: {
      getText: function () { return invoke('editor.getText'); },
      setText: function (text) { invoke('editor.setText', [String(text)]); },
      getSelection: function () { return invoke('editor.getSelection'); },
      setSelection: function (start, end) { invoke('editor.setSelection', [start, end === undefined ? start : end]); },
      replaceSelection: function (text) { invoke('editor.replaceSelection', [String(text)]); },
      insertAt: function (offset, text) { invoke('editor.insertAt', [offset, String(text)]); },
      fileName: function () { return invoke('editor.fileName'); },
      tabs: function () { return invoke('editor.tabs'); },
      openFile: function (path) { invoke('editor.openFile', [String(path)]); }
    },
    fs: {
      read: function (path) { return invoke('fs.read', [String(path)]); },
      write: function (path, text) { invoke('fs.write', [String(path), String(text)]); },
      list: function (path) { return invoke('fs.list', [path === undefined ? '' : String(path)]); },
      exists: function (path) { return invoke('fs.exists', [String(path)]); },
      mkdir: function (path) { invoke('fs.mkdir', [String(path)]); },
      remove: function (path) { invoke('fs.remove', [String(path)]); },
      rename: function (path, newName) { invoke('fs.rename', [String(path), String(newName)]); },
      move: function (path, targetDir) { invoke('fs.move', [String(path), String(targetDir)]); },
      readJson: function (path) { return JSON.parse(invoke('fs.read', [String(path)])); },
      writeJson: function (path, value) { invoke('fs.write', [String(path), JSON.stringify(value, null, 2)]); }
    },
    storage: {
      get: function (key) { return invoke('storage.get', [String(key)]); },
      set: function (key, value) { invoke('storage.set', [String(key), String(value)]); },
      remove: function (key) { invoke('storage.remove', [String(key)]); },
      getJson: function (key) {
        var raw = invoke('storage.get', [String(key)]);
        return raw === null ? null : JSON.parse(raw);
      },
      setJson: function (key, value) { invoke('storage.set', [String(key), JSON.stringify(value)]); }
    },
    ui: {
      toast: function (text) { invoke('ui.toast', [String(text)]); },
      confirm: function (title, text) { return invoke('ui.confirm', [String(title), String(text === undefined ? '' : text)]); },
      prompt: function (title, label, initial) {
        return invoke('ui.prompt', [String(title), String(label === undefined ? '' : label), String(initial === undefined ? '' : initial)]);
      },
      pick: function (title, items) { return invoke('ui.pick', [String(title), items.map(String)]); },
      openPanel: function (id) { return invoke('ui.openPanel', [String(id)]); },
      closePanel: function (id) { invoke('ui.closePanel', [String(id)]); },
      postToPanel: function (id, data) { invoke('ui.postToPanel', [String(id), data === undefined ? null : data]); }
    },
    http: {
      request: function (spec) { return invoke('http.request', [spec]); },
      get: function (url, headers) { return invoke('http.request', [{ url: url, method: 'GET', headers: headers || {} }]); },
      post: function (url, body, headers) {
        return invoke('http.request', [{ url: url, method: 'POST', headers: headers || {}, body: body === undefined ? null : String(body) }]);
      },
      getJson: function (url, headers) {
        return JSON.parse(invoke('http.request', [{ url: url, method: 'GET', headers: headers || {} }]).body);
      },
      postJson: function (url, value, headers) {
        var h = headers || {};
        if (!h['Content-Type']) h['Content-Type'] = 'application/json';
        return JSON.parse(invoke('http.request', [{ url: url, method: 'POST', headers: h, body: JSON.stringify(value) }]).body);
      }
    },
    ai: {
      ask: function (prompt) { return invoke('ai.ask', [String(prompt)]); }
    },
    python: {
      run: function (code) { return invoke('python.run', [String(code)]); }
    },
    clipboard: {
      read: function () { return invoke('clipboard.read'); },
      write: function (text) { invoke('clipboard.write', [String(text)]); }
    },
    panel: {
      post: function (data) { invoke('panel.post', [data === undefined ? null : data]); },
      onMessage: function (fn) {
        g.__vsmPanelMessage = function (json) {
          try { fn(JSON.parse(json)); } catch (e) { report('panel.onMessage', e); }
        };
      }
    },
    on: function (event, fn) { addListener(event, fn, false); },
    once: function (event, fn) { addListener(event, fn, true); },
    off: function (event, fn) { removeListener(event, fn); },
    command: function (id, fn) {
      if (typeof fn !== 'function') throw new Error('Команда должна быть функцией');
      commands[id] = fn;
    },
    commands: {
      run: function (id, ctx) {
        if (!commands[id]) throw new Error('Команда не найдена: ' + id);
        return commands[id](ctx || {});
      }
    }
  };

  g.vsm = vsm;

  g.__vsmEmit = function (event, payloadJson) {
    var payload = payloadJson === undefined || payloadJson === null ? null : JSON.parse(payloadJson);
    var current = (listeners[event] || []).slice();
    current.forEach(function (l) {
      if (l.once) removeListener(event, l.fn);
      try { l.fn(payload); } catch (e) { report('event ' + event, e); }
    });
  };

  g.__vsmCommand = function (id, ctxJson) {
    var ctx = ctxJson ? JSON.parse(ctxJson) : {};
    var result;
    if (commands[id]) {
      result = commands[id](ctx);
    } else if (typeof g.run === 'function') {
      result = g.run(id, ctx);
    } else {
      throw new Error('Команда не найдена: ' + id);
    }
    if (result === undefined || result === null || typeof result.then === 'function') result = {};
    return JSON.stringify(result);
  };

  var moduleCache = {};
  g.require = function (path) {
    var key = String(path).replace(/^\.\//, '');
    if (moduleCache[key]) return moduleCache[key].exports;
    var src = (g.__modules || {})[key];
    if (src === undefined) throw new Error('Модуль не найден: ' + path);
    var module = { exports: {} };
    moduleCache[key] = module;
    new Function('module', 'exports', 'require', src)(module, module.exports, g.require);
    return module.exports;
  };

  if (isEngine) {
    var timers = {};
    var nextTimer = 1;

    function addTimer(fn, ms, repeat) {
      var id = nextTimer++;
      timers[id] = { fn: fn, repeat: repeat };
      invoke('timer.set', [id, Math.max(1, ms | 0), repeat]);
      return id;
    }

    g.setTimeout = function (fn, ms) { return addTimer(fn, ms || 0, false); };
    g.setInterval = function (fn, ms) { return addTimer(fn, Math.max(10, ms || 0), true); };
    g.clearTimeout = g.clearInterval = function (id) {
      delete timers[id];
      invoke('timer.clear', [id]);
    };
    g.__vsmTimer = function (id) {
      var t = timers[id];
      if (!t) return;
      if (!t.repeat) delete timers[id];
      try { t.fn(); } catch (e) { report('timer', e); }
    };

    g.console = {
      log: vsm.log,
      info: vsm.log,
      warn: vsm.log,
      error: vsm.log,
      debug: vsm.log
    };
  }
})(globalThis);
