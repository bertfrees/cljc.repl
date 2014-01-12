#include <dlfcn.h>

extern value_t *VAR_NAME(cljc_DOT_core_SLASH__get_message);
extern value_t *VAR_NAME(cljc_DOT_core_SLASH_str);
extern value_t *VAR_NAME(cljc_DOT_core_SLASH__STAR_1);
extern void init__core();

void repl_init() {
  cljc_init();
  init__core();
}

typedef struct {
  uint32_t status;
  const char * buffer;
} repl_result;

repl_result repl_eval(const char * lib_file, const char * init_fn) {
  static void *handle;
  static void (*fn)();
  static repl_result result;
  handle = dlopen(lib_file, RTLD_GLOBAL);
  fn = dlsym(handle, init_fn);
  jmp_buf exception_env;
  topmost_jmp_buf = &exception_env;
  if (_setjmp(exception_env)) {
    result.status = 1;
    result.buffer = string_get_utf8(
      invoke1(VAR_NAME(cljc_DOT_core_SLASH_str),
              FUNCALL1((closure_t *)VAR_NAME(cljc_DOT_core_SLASH__get_message),
                       get_exception())));
  } else {
    (*fn)();
    topmost_jmp_buf = NULL;
    result.status = 0;
    if (VAR_NAME(cljc_DOT_core_SLASH__STAR_1) == value_nil)
      result.buffer = "nil";
    else
      result.buffer = string_get_utf8(
        invoke1(VAR_NAME(cljc_DOT_core_SLASH_str),
                VAR_NAME(cljc_DOT_core_SLASH__STAR_1)));
  }
  return result;
}
