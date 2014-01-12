#include <dbus/dbus.h>

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

int main(int argc, char** argv) {
	DBusConnection* conn;
	DBusMessage* msg;
	DBusMessage* reply;
	DBusMessageIter args;
	DBusError err;
	dbus_uint32_t serial = 0;
	int ret;
	const char * lib_file;
	const char * init_fn;
	repl_result repl_result;
	dbus_error_init(&err);
	repl_init();
	conn = dbus_bus_get(DBUS_BUS_SESSION, &err);
	CHECK_FOR_ERRORS("Connection error: %s\n");
	ASSERT(conn != NULL);
	ret = dbus_bus_request_name(conn, "cljc.repl.runtime", DBUS_NAME_FLAG_REPLACE_EXISTING , &err);
	CHECK_FOR_ERRORS("Name error: %s\n");
	ASSERT(ret == DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER);
	while (true) {
		dbus_connection_read_write(conn, 0);
		msg = dbus_connection_pop_message(conn);
		if (msg == NULL) {
			usleep(10000);
			continue; }
		if (dbus_message_is_method_call(msg, "cljc.repl.runtime", "eval")) {
			ASSERT(dbus_message_iter_init(msg, &args));
			ASSERT(dbus_message_iter_get_arg_type(&args) == DBUS_TYPE_STRING);
			dbus_message_iter_get_basic(&args, &lib_file);
			ASSERT(dbus_message_iter_next(&args));
			ASSERT(dbus_message_iter_get_arg_type(&args) == DBUS_TYPE_STRING);
			dbus_message_iter_get_basic(&args, &init_fn);
			repl_result = repl_eval(lib_file, init_fn);
			fflush(stdout);
			reply = dbus_message_new_method_return(msg);
			dbus_message_iter_init_append(reply, &args);
			ASSERT(dbus_message_iter_append_basic(&args, DBUS_TYPE_UINT32, &repl_result.status));
			ASSERT(dbus_message_iter_append_basic(&args, DBUS_TYPE_STRING, &repl_result.buffer));
			ASSERT(dbus_connection_send(conn, reply, &serial));
			dbus_connection_flush(conn);
			dbus_message_unref(reply); }
		dbus_message_unref(msg);
	}
}
