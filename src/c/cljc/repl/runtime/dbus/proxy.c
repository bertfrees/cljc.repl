#include <dbus/dbus.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

#define CHECK_FOR_ERRORS(format) do { \
	if (dbus_error_is_set(&err)) { \
		fprintf(stderr, format, err.message); \
		dbus_error_free(&err); \
		exit(1); } \
	} while(0)

#define ASSERT(condition) do { \
	if (!(condition)) { \
		fprintf(stderr, "Unexpected error, aborting...\n"); \
		exit(1); } \
	} while(0)

typedef struct {
  uint32_t status;
  const char * buffer;
} repl_result;

repl_result repl_eval(const char * lib_file, const char * init_fn) {
	static DBusConnection* conn;
	DBusMessage* msg;
	DBusMessageIter args;
	DBusError err;
	DBusPendingCall* pending;
	int ret;
	repl_result repl_result;
	dbus_error_init(&err);
	if (conn == NULL) {
		conn = dbus_bus_get(DBUS_BUS_SESSION, &err);
		CHECK_FOR_ERRORS("Connection error: %s\n");
		ASSERT(conn != NULL);
		ret = dbus_bus_request_name(conn, "cljc.repl.compiler", DBUS_NAME_FLAG_REPLACE_EXISTING , &err);
		CHECK_FOR_ERRORS("Name error: %s\n");
		ASSERT(ret == DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER); }
	msg = dbus_message_new_method_call("cljc.repl.runtime",
	                                   "/cljc/repl/runtime",
	                                   "cljc.repl.runtime",
	                                   "eval");
	ASSERT(msg != NULL);
	dbus_message_iter_init_append(msg, &args);
	ASSERT(dbus_message_iter_append_basic(&args, DBUS_TYPE_STRING, &lib_file));
	ASSERT(dbus_message_iter_append_basic(&args, DBUS_TYPE_STRING, &init_fn));
	ASSERT(dbus_connection_send_with_reply (conn, msg, &pending, -1));
	ASSERT(pending != NULL);
	dbus_connection_flush(conn);
	dbus_message_unref(msg);
	dbus_pending_call_block(pending);
	msg = dbus_pending_call_steal_reply(pending);
	ASSERT(msg != NULL);
	dbus_pending_call_unref(pending);
	ASSERT(dbus_message_iter_init(msg, &args));
	ASSERT(dbus_message_iter_get_arg_type(&args) == DBUS_TYPE_UINT32);
	dbus_message_iter_get_basic(&args, &repl_result.status);
	ASSERT(dbus_message_iter_next(&args));
	ASSERT(dbus_message_iter_get_arg_type(&args) == DBUS_TYPE_STRING);
	dbus_message_iter_get_basic(&args, &repl_result.buffer);
	dbus_message_unref(msg);
	return repl_result;
}
