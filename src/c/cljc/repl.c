#include <dlfcn.h>

void load_and_evaluate(const char * load_lib, const char * eval_fn) {
  static void *handle;
  static void (*fn)();
  handle = dlopen(load_lib, RTLD_GLOBAL);
  fn = dlsym(handle, eval_fn);
  BEGIN_MAIN_CODE;
  (*fn)();
  END_MAIN_CODE;
}
