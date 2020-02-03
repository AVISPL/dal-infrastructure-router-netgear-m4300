package com.avispl.symphony.dal.communicator.netgear;

import com.atlassian.ta.wiremockpactgenerator.WireMockPactGenerator;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

public class NetGearCommunicatorTest {

    static NetGearNASCommunicator netGearCommunicator;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(options().dynamicPort().dynamicHttpsPort().bindAddress("127.0.0.1"));

    {
        wireMockRule.addMockServiceRequestListener(WireMockPactGenerator
                .builder("netgear-adapter", "netgear")
                .withRequestHeaderWhitelist("authorization", "content-type").build());
        wireMockRule.start();
    }

    @BeforeEach
    public void init() throws Exception {
        netGearCommunicator = new NetGearNASCommunicator();
        netGearCommunicator.setProtocol("telnet");
        netGearCommunicator.setHost("172.31.254.27");
        netGearCommunicator.setLogin("dev");
        netGearCommunicator.setPassword("D3vel0pe");
        netGearCommunicator.init();
    }

    @Test
    public void netGearLoginOk() throws Exception {
        List<Statistics> stat = netGearCommunicator.getMultipleStatistics();

        Assert.assertFalse(stat.isEmpty());
        //netGearCommunicator.
    }

    @Test
    public void controlPropertyReloadConfiguration() throws Exception {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Reload configuration");
        netGearCommunicator.controlProperty(controllableProperty);
    }
//
//    @Test
//    @Ignore
    public void controlPropertyReload() throws Exception {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Reload");
        netGearCommunicator.controlProperty(controllableProperty);
    }

    @Test
    public void controlPropertyRestartPort() throws Exception {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Port 1/0/1");
        controllableProperty.setValue("1/0/1");
        netGearCommunicator.controlProperty(controllableProperty);
    }
}
