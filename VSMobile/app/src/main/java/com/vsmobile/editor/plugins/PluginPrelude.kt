package com.vsmobile.editor.plugins

object PluginPrelude {
    val SOURCE: String = """
(function (g) {
  function call(method) {
    var args = Array.prototype.slice.call(arguments, 1);
    var res = JSON.parse(__host.request(method, JSON.stringify(args)));
    if (res.error !== undefined) throw new Error(res.error);
    return res.value;
  }
  function s(v) { return v === undefined || v === null ? '' : String(v); }
  function join(list) {
    return Array.prototype.map.call(list, function (v) {
      if (typeof v === 'string') return v;
      try { return JSON.stringify(v); } catch (e) { return String(v); }
    }).join(' ');
  }
  function logger(level) { return function () { call('log', level, join(arguments)); }; }
  function lookup(name) {
    return (new Function('return typeof ' + name + ' === "function" ? ' + name + ' : undefined;'))();
  }

  g.console = { log: logger('log'), info: logger('info'), warn: logger('warn'), error: logger('error') };

  g.vsm = {
    apiVersion: 2,
    plugin: g.__meta,
    sleep: function (ms) { call('sleep', Number(ms) || 0); },
    project: { name: function () { return call('project.name'); } },
    editor: {
      getText: function () { return call('editor.getText'); },
      setText: function (t) { call('editor.setText', s(t)); },
      getSelection: function () { return call('editor.getSelection'); },
      setSelection: function (a, b) { call('editor.setSelection', a | 0, (b === undefined ? a : b) | 0); },
      getSelectedText: function () {
        var r = call('editor.getSelection');
        return call('editor.getText').slice(r.start, r.end);
      },
      replaceSelection: function (t) { call('editor.replaceSelection', s(t)); },
      getFileName: function () { return call('editor.getFileName'); },
      openFile: function (p) { call('editor.openFile', s(p)); },
      openFiles: function () { return call('editor.openFiles'); },
      save: function () { call('editor.save'); }
    },
    fs: {
      list: function (p) { return call('fs.list', s(p)); },
      read: function (p) { return call('fs.read', s(p)); },
      readJson: function (p) { return JSON.parse(call('fs.read', s(p))); },
      exists: function (p) { return call('fs.exists', s(p)); },
      write: function (p, t) { call('fs.write', s(p), s(t)); },
      writeJson: function (p, v, indent) { call('fs.write', s(p), JSON.stringify(v, null, indent === undefined ? 2 : indent)); },
      mkdir: function (p) { call('fs.mkdir', s(p)); },
      remove: function (p) { call('fs.remove', s(p)); },
      rename: function (p, n) { call('fs.rename', s(p), s(n)); }
    },
    ui: {
      toast: function (t) { call('ui.toast', s(t)); },
      alert: function (title, message) { call('ui.alert', s(title), s(message)); },
      confirm: function (title, message) { return call('ui.confirm', s(title), s(message)); },
      prompt: function (title, label, initial) { return call('ui.prompt', s(title), s(label), s(initial)); },
      select: function (title, items) { return call('ui.select', s(title), Array.prototype.map.call(items, String)); }
    },
    http: {
      fetch: function (url, options) {
        var res = call('http.fetch', s(url), options || {});
        res.text = function () { return res.body; };
        res.json = function () { return JSON.parse(res.body); };
        return res;
      }
    },
    storage: {
      get: function (key, fallback) {
        var v = call('storage.get', s(key));
        return v === null ? fallback : JSON.parse(v);
      },
      set: function (key, value) { call('storage.set', s(key), JSON.stringify(value)); },
      remove: function (key) { call('storage.remove', s(key)); },
      keys: function () { return call('storage.keys'); }
    },
    ai: { ask: function (prompt, system) { return call('ai.ask', s(prompt), s(system)); } },
    clipboard: {
      read: function () { return call('clipboard.read'); },
      write: function (t) { call('clipboard.write', s(t)); }
    }
  };

  var cache = {};
  g.require = function (name) {
    var key = String(name).replace(/^\.\//, '');
    if (cache[key]) return cache[key].exports;
    var src = g.__modules[key];
    if (src === undefined) src = g.__modules[key + '.js'];
    if (src === undefined) throw new Error('Модуль не найден: ' + name);
    var mod = { exports: {} };
    cache[key] = mod;
    (new Function('module', 'exports', 'require', src))(mod, mod.exports, g.require);
    return mod.exports;
  };

  g.__dispatch = function (kind, name, payload) {
    var r;
    if (kind === 'command') {
      var run = lookup('run');
      if (!run) throw new Error('В плагине нет функции run');
      r = run(name, payload);
    } else {
      var onEvent = lookup('onEvent');
      if (onEvent) r = onEvent(name, payload);
    }
    return JSON.stringify(r === undefined ? null : r);
  };
})(this);
""".trimIndent()
}
