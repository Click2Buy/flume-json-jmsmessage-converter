package com.marketconnect.flume.source.jms;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import javax.jms.BytesMessage;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.TextMessage;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.apache.flume.event.SimpleEvent;
import org.apache.flume.source.jms.JMSMessageConverter;
import org.apache.flume.source.jms.JMSSourceConfiguration;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.ReadContext;
import net.minidev.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonJMSMessageConverter implements JMSMessageConverter {
    private static final Logger logger =
            LoggerFactory.getLogger(JsonJMSMessageConverter.class);

  // Config vars
  /** Json used to create Body. If it is a Json Array it will create one event per object in the array. */
  public static final String BODY_CONFIG = "body";
  public static final String BODY_DEFAULT = "$.";

  /** List of json to use in an event header. */
  public static final String HEADERS_CONFIG = "headers";
  public static final String NAME_CONFIG = "name";
  public static final String JSON_CONFIG = "json";

  private final Charset charset;
  private final String body;
  private final Map<String, String> jsonHeaders;

  private JsonJMSMessageConverter(String charset, String body, Map<String, String> jsonHeaders) {
    this.charset = Charset.forName(charset);
    this.body = body;
    this.jsonHeaders = jsonHeaders;
    logger.debug("Body is " + body + ", charset " + charset + ", json headers are " + jsonHeaders);
  }

  public static class Builder implements JMSMessageConverter.Builder {
    @Override
    public JMSMessageConverter build(Context context) {
      String headersStr = context.getString(JsonJMSMessageConverter.HEADERS_CONFIG, null);
      Map<String, String> jsonHeaders = new HashMap<String, String>();
      if (headersStr != null) {
          String[] headers = headersStr.split(" ");
          Context headersContext = new Context(context.getSubProperties(JsonJMSMessageConverter.HEADERS_CONFIG + "."));
          for (int i = 0; i < headers.length; i++) {
              Context header = new Context(headersContext.getSubProperties(headers[i] + "."));
              String name = header.getString(JsonJMSMessageConverter.NAME_CONFIG);
              String json = header.getString(JsonJMSMessageConverter.JSON_CONFIG);
              jsonHeaders.put(name, json);
          }
      }
      return new JsonJMSMessageConverter(context.getString(
          JMSSourceConfiguration.CONVERTER_CHARSET,
          JMSSourceConfiguration.CONVERTER_CHARSET_DEFAULT).trim(),
                                         context.getString(
          JsonJMSMessageConverter.BODY_CONFIG,
          JsonJMSMessageConverter.BODY_DEFAULT).trim(),
                                         jsonHeaders);
    }
  }

  @Override
  public List<Event> convert(Message message) throws JMSException {
    Event event = new SimpleEvent();
    Map<String, String> headers = event.getHeaders();
    @SuppressWarnings("rawtypes")
    Enumeration propertyNames = message.getPropertyNames();
    while (propertyNames.hasMoreElements()) {
      String name = propertyNames.nextElement().toString();
      String value = message.getStringProperty(name);
      headers.put(name, value);
    }
    String json = "";
    if (message instanceof BytesMessage) {
      BytesMessage bytesMessage = (BytesMessage)message;
      long length = bytesMessage.getBodyLength();
      if (length > 0L) {
        if (length > Integer.MAX_VALUE) {
          throw new JMSException("Unable to process message " + "of size "
              + length);
        }
        byte[] body = new byte[(int)length];
        int count = bytesMessage.readBytes(body);
        if (count != length) {
          throw new JMSException("Unable to read full message. " +
              "Read " + count + " of total " + length);
        }
        json = new String(body, charset);
      }
    } else if (message instanceof TextMessage) {
      TextMessage textMessage = (TextMessage)message;
      json = textMessage.getText();
    }
    List<Event> events = new ArrayList<Event>();
    logger.debug("Json is " + json);
    if (json != null && !json.isEmpty()) {
        ReadContext ctx = JsonPath.parse(json);
        if (jsonHeaders.size() > 0) {
            for (Map.Entry<String, String> entry : jsonHeaders.entrySet()) {
                String name = entry.getKey();
                try {
                    String jsonPath = entry.getValue();
                    String value = ctx.read(jsonPath);
                    if (value != null) {
                        headers.put(name, value);
                    }
                } catch (PathNotFoundException e) {
                    logger.error(e.toString());
                }
            }
        }
        try {
            Object result = ctx.read(body);
            if (result instanceof List) {
                Iterator it = ((List) result).iterator();
                while (it.hasNext()) {
                    Event e = new SimpleEvent();
                    e.setHeaders(headers);
                    String jsonStr = JSONObject.toJSONString((Map) it.next());
                    e.setBody(jsonStr.getBytes(charset));
                    events.add(e);
                }
            } else if (result instanceof Map) {
                String jsonStr = JSONObject.toJSONString((Map) result);
                event.setBody(jsonStr.getBytes(charset));
                events.add(event);
            } else {
                throw new JMSException("Json body path doesnt match anything");
            }
        } catch (PathNotFoundException e) {
            logger.error(e.toString());
        }
    }
    logger.debug("Sending " + events.size() + " events");
    return events;
  }
}
