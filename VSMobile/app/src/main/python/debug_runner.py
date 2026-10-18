import sys
import io
import builtins
import threading
import traceback

_input_callback = None
_run_thread_id = None
_current_output = None


class _Capture(io.StringIO):
    limit = 200000

    def __init__(self):
        super().__init__()
        self.truncated = False

    def write(self, s):
        if self.tell() >= self.limit:
            self.truncated = True
            return len(s)
        return super().write(s)


def set_input_callback(cb):
    global _input_callback
    _input_callback = cb


def _patched_input(prompt=""):
    if _input_callback is None:
        raise EOFError("input() не настроен")
    prompt = str(prompt)
    sys.stdout.write(prompt)
    snapshot = _current_output.getvalue() if _current_output is not None else ""
    value = _input_callback.call(prompt, snapshot)
    if value is None:
        sys.stdout.write("\n")
        raise EOFError("ввод отменён")
    value = str(value)
    sys.stdout.write(value + "\n")
    return value


builtins.input = _patched_input


def stop_code():
    tid = _run_thread_id
    if tid is None:
        return False
    try:
        import ctypes
        res = ctypes.pythonapi.PyThreadState_SetAsyncExc(
            ctypes.c_ulong(tid), ctypes.py_object(KeyboardInterrupt)
        )
        if res > 1:
            ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(tid), None)
            return False
        return res == 1
    except Exception:
        return False


def _finish(output, error):
    text = output.getvalue()
    if output.truncated:
        text += "\n[вывод обрезан]"
    return [text, error]


def run_code(code, breakpoints=None):
    global _run_thread_id, _current_output
    output = _Capture()
    _current_output = output
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    sys.stdout = output
    sys.stderr = output
    _run_thread_id = threading.get_ident()

    try:
        if breakpoints:
            lines = code.split('\n')
            for bp in breakpoints:
                if 1 <= bp <= len(lines):
                    output.write(f"[BREAKPOINT] Строка {bp}: {lines[bp - 1].strip()}\n")

        try:
            compiled = compile(code, '<user_code>', 'exec')
        except SyntaxError:
            etype, value, _ = sys.exc_info()
            return _finish(output, "".join(traceback.format_exception_only(etype, value)))

        try:
            exec(compiled, {'__name__': '__main__'})
        except SystemExit:
            return _finish(output, None)
        except BaseException:
            etype, value, tb = sys.exc_info()
            if tb is not None and tb.tb_next is not None:
                tb = tb.tb_next
            return _finish(output, "".join(traceback.format_exception(etype, value, tb)))

        return _finish(output, None)
    finally:
        _run_thread_id = None
        _current_output = None
        sys.stdout = old_stdout
        sys.stderr = old_stderr
