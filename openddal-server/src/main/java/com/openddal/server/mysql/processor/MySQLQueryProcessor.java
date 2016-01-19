package com.openddal.server.mysql.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openddal.server.Request;
import com.openddal.server.Response;
import com.openddal.server.processor.AbstractProtocolProcessor;
import com.openddal.server.processor.ProtocolProcessException;

/**
 * 
 * @author <a href="mailto:jorgie.mail@gmail.com">jorgie li</a>
 *
 */
public class MySQLQueryProcessor extends AbstractProtocolProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(MySQLQueryProcessor.class);

    @Override
    protected void doProcess(Request request, Response response) throws ProtocolProcessException {
        
        
    }

}
