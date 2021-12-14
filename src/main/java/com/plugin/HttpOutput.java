package com.plugin;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.*;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
import org.apache.commons.collections.FastArrayList;
import org.apache.commons.collections4.ListUtils;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.BooleanField;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.TextField;
import org.graylog2.plugin.outputs.MessageOutput;
import org.graylog2.plugin.streams.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

/**
 * This is the plugin. Your class should implement one of the existing plugin
 * interfaces. (i.e. AlarmCallback, MessageInput, MessageOutput)
 */
public class HttpOutput implements MessageOutput {
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();
    private boolean shutdown;
    private boolean raise_exception_on_http_error = false;
    private String url;
    private String intake_key;
    private static final String CK_OUTPUT_API = "output_api";
    private static final String CK_INTAKE_KEY = "intake_key";
    private static final String CK_RAISE_EXCEPTION = "raise_exception";
    private static final Logger LOG = LoggerFactory.getLogger(HttpOutput.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Inject
    public HttpOutput(@Assisted Stream stream, @Assisted Configuration conf) throws HttpOutputException {

        this.url = conf.getString(CK_OUTPUT_API);
        this.intake_key = conf.getString(CK_INTAKE_KEY);
        this.raise_exception_on_http_error = conf.getBoolean(CK_RAISE_EXCEPTION);

        this.shutdown = false;
        LOG.info(" Http Output Plugin has been configured with the following parameters:");
        LOG.info(CK_OUTPUT_API + " : " + this.url);
        LOG.info(CK_INTAKE_KEY + " : " + this.intake_key);
        LOG.info(CK_RAISE_EXCEPTION + " : " + this.raise_exception_on_http_error);

        try {
            new URL(this.url);
        } catch (MalformedURLException e) {
            LOG.info("Error in the given API", e);
            throw new HttpOutputException("Error while constructing the API.", e);
        }

        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS);

        this.httpClient = clientBuilder.build();
    }

    @Override
    public boolean isRunning() {
        return !this.shutdown;
    }

    @Override
    public void stop() {
        this.shutdown = true;

    }

    @Override
    public void write(List<Message> msgs) throws Exception {

        for(Message message: msgs) {
            this.write(message);
        }
    }

    @Override
    public void write(Message msg) throws Exception {

        Map<String, Object> payload = new HashMap<>();
        payload.put("intake_key", this.intake_key);
        payload.put("json", this.gson.toJson(msg.getFields()));

        this.executeRequest(
                            RequestBody.create(
                                               JSON,
                                               this.gson.toJson(payload)
                                               )
                            );
    }

    private void executeRequest(RequestBody requestBody) throws HttpOutputException, IOException {
        if (this.shutdown) {
            return;
        }

        Request request = new Request.Builder()
            .url(this.url)
            .post(requestBody)
            .build();

        // ensure the response (and underlying response body) is closed
        try (Response response = this.httpClient.newCall(request).execute()) {
            if (response.code() > 399) {
                LOG.info("Unexpected HTTP response status " + response.code());
                if (this.raise_exception_on_http_error) {
                    throw new HttpOutputException("Unexpected HTTP response status " + response.code());
                }
            }
        }
    }


    public interface Factory extends MessageOutput.Factory<HttpOutput> {
        @Override
        HttpOutput create(Stream stream, Configuration configuration);

        @Override
        Config getConfig();

        @Override
        Descriptor getDescriptor();
    }

    public static class Descriptor extends MessageOutput.Descriptor {
        public Descriptor() {
            super("HttpOutput Output", false, "", "Forwards stream to HTTP.");
        }
    }

    public static class Config extends MessageOutput.Config {
        @Override
        public ConfigurationRequest getRequestedConfiguration() {
            final ConfigurationRequest configurationRequest = new ConfigurationRequest();

            configurationRequest.addField(
                                          new TextField(CK_OUTPUT_API, "API to forward the stream data.", "/",
                                                        "HTTP address where the stream data to be sent.", ConfigurationField.Optional.NOT_OPTIONAL));

            configurationRequest.addField(
                                          new TextField(CK_INTAKE_KEY, "Intake key", "",
                                                        "The intake key to identify the events", ConfigurationField.Optional.NOT_OPTIONAL));

            configurationRequest.addField(new BooleanField(CK_RAISE_EXCEPTION, "Raise exception", false,
                    "Raise an exception on HTTP error"));

            return configurationRequest;
        }
    }

    public class HttpOutputException extends Exception {

        private static final long serialVersionUID = -5301266791901423492L;

        public HttpOutputException(String msg) {
            super(msg);
        }

        public HttpOutputException(String msg, Throwable cause) {
            super(msg, cause);
        }

    }
}
