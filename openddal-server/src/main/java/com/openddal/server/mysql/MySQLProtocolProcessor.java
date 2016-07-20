package com.openddal.server.mysql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openddal.server.ProtocolProcessException;
import com.openddal.server.ProtocolTransport;
import com.openddal.server.TraceableProcessor;
import com.openddal.server.mysql.auth.Privilege;
import com.openddal.server.mysql.auth.PrivilegeDefault;
import com.openddal.server.mysql.parser.ServerParse;
import com.openddal.server.mysql.parser.ServerParseSelect;
import com.openddal.server.mysql.parser.ServerParseSet;
import com.openddal.server.mysql.parser.ServerParseShow;
import com.openddal.server.mysql.parser.ServerParseStart;
import com.openddal.server.mysql.proto.ColumnPacket;
import com.openddal.server.mysql.proto.Com_Initdb;
import com.openddal.server.mysql.proto.Com_Query;
import com.openddal.server.mysql.proto.ERR;
import com.openddal.server.mysql.proto.Flags;
import com.openddal.server.mysql.proto.OK;
import com.openddal.server.mysql.proto.Packet;
import com.openddal.server.mysql.proto.ResultSetPacket;
import com.openddal.server.mysql.proto.RowPacket;
import com.openddal.server.mysql.respo.CharacterSet;
import com.openddal.server.mysql.respo.SelectVariables;
import com.openddal.server.mysql.respo.ShowCharset;
import com.openddal.server.mysql.respo.ShowEngines;
import com.openddal.server.mysql.respo.ShowVariables;
import com.openddal.server.mysql.respo.ShowVersion;
import com.openddal.server.mysql.respo.TxResultSet;
import com.openddal.server.util.ErrorCode;
import com.openddal.server.util.MysqlDefs;
import com.openddal.server.util.ResultSetUtil;
import com.openddal.server.util.StringUtil;
import com.openddal.util.JdbcUtils;
import com.openddal.util.New;
import com.openddal.util.StringUtils;

import io.netty.buffer.ByteBuf;

public class MySQLProtocolProcessor extends TraceableProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MySQLProtocolProcessor.class);

    protected void doProcess(ProtocolTransport transport) throws Exception {

        ByteBuf buffer = transport.in;
        byte[] packet = new byte[buffer.readableBytes()];
        buffer.readBytes(packet);
        setSequenceId(Packet.getSequenceId(packet));
        byte type = Packet.getType(packet);

        switch (type) {
        case Flags.COM_INIT_DB:
            String db = Com_Initdb.loadFromPacket(packet).schema;
            getTrace().protocol("COM_INIT_DB").sql(db);
            initDB(db);
            sendOk();
            break;
        case Flags.COM_QUERY:
            String query = Com_Query.loadFromPacket(packet).query;
            getTrace().protocol("COM_QUERY").sql(query);
            query(query);
            break;
        case Flags.COM_PING:
            getTrace().protocol("COM_PING");
            sendOk();
            break;
        case Flags.COM_QUIT:
            getTrace().protocol("COM_QUIT");
            getSession().close();
            getProtocolTransport().close();
            break;
        case Flags.COM_PROCESS_KILL:
            getTrace().protocol("COM_PROCESS_KILL");
        case Flags.COM_STMT_PREPARE:
            getTrace().protocol("COM_STMT_PREPARE");
        case Flags.COM_STMT_EXECUTE:
            getTrace().protocol("COM_STMT_EXECUTE");
        case Flags.COM_STMT_CLOSE:
            getTrace().protocol("COM_STMT_CLOSE");
            break;
        case Flags.COM_SLEEP:// deprecated
            getTrace().protocol("COM_SLEEP");
        case Flags.COM_FIELD_LIST:
            getTrace().protocol("COM_FIELD_LIST");
        case Flags.COM_CREATE_DB:
            getTrace().protocol("COM_CREATE_DB");
        case Flags.COM_DROP_DB:
            getTrace().protocol("COM_DROP_DB");
        case Flags.COM_REFRESH:
            getTrace().protocol("COM_REFRESH");
        case Flags.COM_SHUTDOWN:
            getTrace().protocol("COM_SHUTDOWN");
        case Flags.COM_STATISTICS:
            getTrace().protocol("COM_STATISTICS");
        case Flags.COM_PROCESS_INFO: // deprecated
            getTrace().protocol("COM_PROCESS_INFO");
        case Flags.COM_CONNECT:// deprecated
            getTrace().protocol("COM_CONNECT");
        case Flags.COM_DEBUG:
            getTrace().protocol("COM_DEBUG");
        case Flags.COM_TIME:// deprecated
            getTrace().protocol("COM_TIME");
        case Flags.COM_DELAYED_INSERT:// deprecated
            getTrace().protocol("COM_DELAYED_INSERT");
        case Flags.COM_CHANGE_USER:
            getTrace().protocol("COM_CHANGE_USER");
        case Flags.COM_BINLOG_DUMP:
            getTrace().protocol("COM_BINLOG_DUMP");
        case Flags.COM_TABLE_DUMP:
            getTrace().protocol("COM_TABLE_DUMP");
        case Flags.COM_CONNECT_OUT:
            getTrace().protocol("COM_CONNECT_OUT");
        case Flags.COM_REGISTER_SLAVE:
            getTrace().protocol("COM_REGISTER_SLAVE");
        case Flags.COM_STMT_SEND_LONG_DATA:
            getTrace().protocol("COM_STMT_SEND_LONG_DATA");
        case Flags.COM_STMT_RESET:
            getTrace().protocol("COM_STMT_CLOSE");
        case Flags.COM_SET_OPTION:
            getTrace().protocol("COM_STMT_RESET");
        case Flags.COM_STMT_FETCH:
            getTrace().protocol("COM_STMT_FETCH");
        case Flags.COM_DAEMON: // deprecated
            getTrace().protocol("COM_DAEMON");
        case Flags.COM_BINLOG_DUMP_GTID:
            getTrace().protocol("COM_BINLOG_DUMP_GTID");
        case Flags.COM_END:
            getTrace().protocol("COM_END");
            throw new ProtocolProcessException(ErrorCode.ER_NOT_SUPPORTED_YET, "Command not supported yet");
        default:
            throw new ProtocolProcessException(ErrorCode.ER_UNKNOWN_COM_ERROR, "Unknown command");
        }
    }

    public void query(String sql) throws Exception {
        if (StringUtils.isNullOrEmpty(sql)) {
            throw error(ErrorCode.ER_NOT_ALLOWED_COMMAND, "Empty SQL");
        }

        int rs = ServerParse.parse(sql);
        switch (rs & 0xff) {
        case ServerParse.SET:
            processSet(sql, rs >>> 8);
            break;
        case ServerParse.SHOW:
            processShow(sql, rs >>> 8);
            break;
        case ServerParse.SELECT:
            processSelect(sql, rs >>> 8);
            break;
        case ServerParse.START:
            processStart(sql, rs >>> 8);
            break;
        case ServerParse.BEGIN:
            processBegin(sql, rs >>> 8);
            break;
        case ServerParse.LOAD:
            processSavepoint(sql, rs >>> 8);
            break;
        case ServerParse.SAVEPOINT:
            processSavepoint(sql, rs >>> 8);
            break;
        case ServerParse.USE:
            processUse(sql, rs >>> 8);
            break;
        case ServerParse.COMMIT:
            processCommit(sql, rs >>> 8);
            break;
        case ServerParse.ROLLBACK:
            processRollback(sql, rs >>> 8);
            break;
        case ServerParse.EXPLAIN:
            execute(sql, ServerParse.EXPLAIN);
            break;
        default:
            execute(sql, rs);
        }
    }

    public void initDB(String db) throws Exception {
        Privilege privilege = PrivilegeDefault.getPrivilege();
        if (db == null || !privilege.schemaExists(getSession().getUser(), db)) {
            throw error(ErrorCode.ER_BAD_DB_ERROR, "Unknown database '" + db + "'");
        }
        ResultSet rs = null;
        List<String> schemas;
        try {
            rs = getConnection().getMetaData().getSchemas();
            schemas = New.arrayList();
            while (rs.next()) {
                schemas.add(rs.getString("SCHEMA_NAME"));
            }
        } finally {
            JdbcUtils.closeSilently(rs);
        }
        if (!schemas.contains(db)) {
            throw error(ErrorCode.ER_BAD_DB_ERROR, "Unknown database '" + db + "'");
        }
        execute("SET SCHEMA = " + db, ServerParse.OTHER);
        sendOk();

    }
    
    private void processCommit(String sql, int offset) throws Exception {
        try {
            getConnection().commit();
            sendOk();
        } catch (SQLException e) {
            throw error(ErrorCode.ERR_HANDLE_DATA, e);
        }

    }

    private void processRollback(String sql, int offset) throws Exception {
        getConnection().rollback();
        sendOk();
    }

    private void processUse(String sql, int offset) throws Exception {
        String schema = sql.substring(offset).trim();
        int length = schema.length();
        if (length > 0) {
            if (schema.endsWith(";"))
                schema = schema.substring(0, schema.length() - 1);
            schema = StringUtil.replaceChars(schema, "`", null);
            length = schema.length();
            if (schema.charAt(0) == '\'' && schema.charAt(length - 1) == '\'') {
                schema = schema.substring(1, length - 1);
            }
        }
        initDB(schema);
    }

    private void processBegin(String sql, int offset) throws Exception {
        unsupported(sql);
    }

    private void processSavepoint(String sql, int offset) throws Exception {
        unsupported(sql);

    }

    private void processStart(String sql, int offset) throws Exception {
        switch (ServerParseStart.parse(sql, offset)) {
        case ServerParseStart.TRANSACTION:
            unsupported("Start TRANSACTION");
            break;
        default:
            execute(sql, ServerParse.START);
        }

    }

    public void processSet(String stmt, int offset) throws Exception {
        Connection c = getConnection();
        int rs = ServerParseSet.parse(stmt, offset);
        switch (rs & 0xff) {
        case ServerParseSet.AUTOCOMMIT_ON:
            if (!c.getAutoCommit()) {
                c.setAutoCommit(true);
            }
            sendOk();
            break;
        case ServerParseSet.AUTOCOMMIT_OFF: {
            if (c.getAutoCommit()) {
                c.setAutoCommit(false);
            }
            sendOk();
            break;
        }
        case ServerParseSet.TX_READ_UNCOMMITTED: {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
            sendOk();
            break;
        }
        case ServerParseSet.TX_READ_COMMITTED: {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            sendOk();
            break;
        }
        case ServerParseSet.TX_REPEATED_READ: {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            sendOk();
            break;
        }
        case ServerParseSet.TX_SERIALIZABLE: {
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            sendOk();
            break;
        }
        case ServerParseSet.NAMES:
            String charset = stmt.substring(rs >>> 8).trim();
            if (getSession().setCharset(charset)) {
                sendOk();
            } else {
                sendError(ErrorCode.ER_UNKNOWN_CHARACTER_SET, "Unknown charset '" + charset + "'");
            }
            break;
        case ServerParseSet.CHARACTER_SET_CLIENT:
        case ServerParseSet.CHARACTER_SET_CONNECTION:
        case ServerParseSet.CHARACTER_SET_RESULTS:
            CharacterSet.response(stmt, this, rs);
            break;
        case ServerParseSet.AT_VAR:
            execute(stmt, ServerParse.SET);
            break;
        default:
            StringBuilder s = new StringBuilder();
            LOGGER.warn(s.append(stmt).append(" is not executed").toString());
            sendOk();
        }
    }

    public void processShow(String stmt, int offset) throws Exception {
        ResultSet rs = null;
        try {
            switch (ServerParseShow.parse(stmt, offset)) {
                case ServerParseShow.DATABASES:
                    execute("SHOW DATABASES", ServerParse.SHOW);
                    break;
                case ServerParseShow.FULL_TABKES:
                    execute("SHOW TABLES", ServerParse.SHOW);
                    break;
                case ServerParseShow.CHARSET:
                    sendResultSet(ShowCharset.getResultSet());
                    break;
                case ServerParseShow.COLLATION:
                    sendResultSet(ShowCharset.getCollationResultSet());
                    break;
                case ServerParseShow.CONNECTION:
                    unsupported("CONNECTION");
                    break;
                case ServerParseShow.SLOW:
                    unsupported("SLOW");
                    break;
                case ServerParseShow.PHYSICAL_SLOW:
                    unsupported("PHYSICAL_SLOW");
                    break;
                case ServerParseShow.VARIABLES:
                    sendResultSet(ShowVariables.getResultSet());
                    break;
                case ServerParseShow.SESSION_STATUS:
                case ServerParseShow.SESSION_VARIABLES:
                    sendResultSet(ShowVariables.getShowResultSet(stmt));
                    break;
                case ServerParseShow.ENGINES:
                    sendResultSet(ShowEngines.getResultSet());
                    break;
                default:
                    execute(stmt, ServerParse.SHOW);
            }
        } finally {
            JdbcUtils.closeSilently(rs);
        }
    }

    public void processSelect(String stmt, int offs) throws Exception {
        switch (ServerParseSelect.parse(stmt, offs)) {
        case ServerParseSelect.VERSION_COMMENT:
            sendResultSet(ShowVersion.getCommentResultSet());
            break;
        case ServerParseSelect.DATABASE:
            execute("SELECT SCHEMA()", ServerParse.SELECT);
            break;
        case ServerParseSelect.CONNECTION_ID:
            execute("SELECT SESSION_ID()", ServerParse.SELECT);
            break;
        case ServerParseSelect.USER:
            execute("SELECT USER()", ServerParse.SELECT);
            break;
        case ServerParseSelect.SESSION_TX_READ_ONLY:
            sendResultSet(TxResultSet.getReadonlyResultSet(getConnection().isReadOnly()));
            break;
        case ServerParseSelect.SESSION_ISOLATION:
            sendResultSet(TxResultSet.getIsolationResultSet(getConnection().getTransactionIsolation()));
            break;
        case ServerParseSelect.VERSION:
            sendResultSet(ShowVersion.getResultSet());
            break;
        case ServerParseSelect.LAST_INSERT_ID:
            execute("SELECT LAST_INSERT_ID()", ServerParse.SELECT);
            break;
        case ServerParseSelect.IDENTITY:
            execute("SELECT SCOPE_IDENTITY()", ServerParse.SELECT);
            break;
        case ServerParseSelect.SELECT_SESSION_VARIABLES:
            sendResultSet(SelectVariables.getResultSet(stmt));
            break;
        default:
            execute(stmt, ServerParse.SELECT);
        }
    }

    public void processKill(String stmt, int offset) throws Exception {
        String id = stmt.substring(offset).trim();
        if (StringUtils.isNullOrEmpty(id)) {
            sendError(ErrorCode.ER_NO_SUCH_THREAD, "NULL connection id");
        } else {
            try {
                //long value = Long.parseLong(id);
                execute("SELECT CANCEL_SESSION()", ServerParse.SELECT);
            } catch (NumberFormatException e) {
                throw error(ErrorCode.ER_NO_SUCH_THREAD, "Invalid connection id:" + id);
            }
            throw error(ErrorCode.ER_NO_SUCH_THREAD, "Unknown connection id:" + id);
        }
    }

    private void execute(String sql, int type) throws Exception {
        Connection conn = getSession().getEngineConnection();
        Statement stmt = null;
        ResultSet rs = null;
        switch (type) {
        case ServerParse.SELECT:
        // "show" as "select" query
        // @author little-pan
        // @since 2016-07-13
        case ServerParse.SHOW:
        case ServerParse.EXPLAIN:
            try {
                stmt = conn.createStatement();
                rs = stmt.executeQuery(sql);
                sendResultSet(rs);
            } finally {
                JdbcUtils.closeSilently(stmt);
                JdbcUtils.closeSilently(rs);
            }
            break;
        case ServerParse.SET:
        case ServerParse.INSERT:
        case ServerParse.DELETE:
        case ServerParse.UPDATE:
        case ServerParse.REPLACE:
            try {
                stmt = conn.createStatement();
                stmt.execute(sql);
                sendOk();
            } finally {
                JdbcUtils.closeSilently(stmt);
                JdbcUtils.closeSilently(rs);
            }
            break;

        default:
            try {
                stmt = conn.createStatement();
                stmt.execute(sql);
                sendOk();
            } finally {
                JdbcUtils.closeSilently(stmt);
                JdbcUtils.closeSilently(rs);
            };
        }
    }

    private long getNextSequenceId() {
        Long seq = getSession().getAttachment("sequenceId");
        if (seq == null) {
            seq = 0L;
        }
        return ++seq;
    }

    private void setSequenceId(long sequenceId) {
        getSession().setAttachment("sequenceId", sequenceId);
    }

    private void unsupported(String msg) throws Exception {
        throw error(ErrorCode.ER_SYNTAX_ERROR, 
                msg + " unsupported");
    }
    
    private Exception error(int errno, Throwable e) {
        return new ProtocolProcessException(errno, e);
    }
    
    private Exception error(int errno, String msg) {
        return new ProtocolProcessException(errno, msg);
    }

    public void sendOk() {
        OK ok = new OK();
        ok.sequenceId = getNextSequenceId();
        ok.setStatusFlag(Flags.SERVER_STATUS_AUTOCOMMIT);
        getProtocolTransport().out.writeBytes(ok.toPacket());
    }

    public void sendError(int errno, String msg) {
        ERR err = new ERR();
        err.sequenceId = getNextSequenceId();
        err.errorCode = errno;
        err.errorMessage = msg;
        getProtocolTransport().out.writeBytes(err.toPacket());
    }

    /**
     * @see https://dev.mysql.com/doc/internals/en/com-query-response.html
     * 
     * @param rs
     * @throws Exception
     */
    public void sendResultSet(ResultSet rs) throws Exception {
        ResultSetMetaData metaData = rs.getMetaData();
        int colunmCount = metaData.getColumnCount();

        ResultSetPacket resultset = new ResultSetPacket();
        resultset.sequenceId = getNextSequenceId();
        ResultSetPacket.characterSet = getSession().getCharsetIndex();

        for (int i = 0; i < colunmCount; i++) {
            int j = i + 1;
            ColumnPacket columnPacket = new ColumnPacket();
            columnPacket.org_name = StringUtil.emptyIfNull(metaData.getColumnName(j));
            columnPacket.name = StringUtil.emptyIfNull(metaData.getColumnLabel(j));
            columnPacket.org_table = StringUtil.emptyIfNull(metaData.getTableName(j));
            columnPacket.table = StringUtil.emptyIfNull(metaData.getTableName(j));
            columnPacket.schema = StringUtil.emptyIfNull(metaData.getSchemaName(j));
            columnPacket.flags = ResultSetUtil.toFlag(metaData, j);
            columnPacket.columnLength = metaData.getColumnDisplaySize(j);
            columnPacket.decimals = metaData.getScale(j);
            int javaType = MysqlDefs.javaTypeDetect(metaData.getColumnType(j), (int) columnPacket.decimals);
            columnPacket.type = (byte) (MysqlDefs.javaTypeMysql(javaType) & 0xff);
            resultset.addColumn(columnPacket);
        }

        while (rs.next()) {
            RowPacket rowPacket = new RowPacket();
            for (int i = 0; i < colunmCount; i++) {
                int j = i + 1;
                rowPacket.data.add(StringUtil.emptyIfNull(rs.getString(j)));
            }
            resultset.addRow(rowPacket);
        }
        ByteBuf out = getProtocolTransport().out;
        ArrayList<byte[]> packets = resultset.toPackets();
        for (byte[] bs : packets) {
            out.writeBytes(bs);
        }
    }

}
