package com.avispl.symphony.dal.communicator.netgear;

import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

@Tag("integration")
public class NetGearCommunicatorIntegrationTest {

    static NetGearNASCommunicator netGearCommunicator;

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
    public void getMultipleStatistics() throws Exception {
        long startTime = System.currentTimeMillis();
        List<Statistics> stat = netGearCommunicator.getMultipleStatistics();
        long endTime = System.currentTimeMillis();
        Assert.assertFalse(stat.isEmpty());
        long diff = endTime - startTime;
        Assert.assertFalse(((ExtendedStatistics)stat.get(0)).getStatistics().isEmpty());
        Assert.assertFalse(((ExtendedStatistics)stat.get(0)).getControl().isEmpty());
        Assert.assertEquals("Up", ((ExtendedStatistics)stat.get(0)).getStatistics().get("IPv4 Interface Status"));
        Assert.assertEquals("Push", ((ExtendedStatistics)stat.get(0)).getStatistics().get("Reload"));
        Assert.assertEquals("Push", ((ExtendedStatistics)stat.get(0)).getControl().get("Reload"));
        Assert.assertEquals("Toggle", ((ExtendedStatistics)stat.get(0)).getControl().get("Port Controls#Port 1/0/1"));
    }

    @Test
    public void controlPropertyStartupPort() throws Exception {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Port Controls#Port 1/0/1");
        controllableProperty.setValue("1");
        netGearCommunicator.controlProperty(controllableProperty);
        Assert.assertEquals(((ExtendedStatistics)netGearCommunicator.getMultipleStatistics().get(0)).getStatistics().get("Port Controls#Port 1/0/1"), "true");
    }

    @Test
    public void controlPropertyShutdownPort() throws Exception {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Port Controls#Port 1/0/1");
        controllableProperty.setValue("0");
        netGearCommunicator.controlProperty(controllableProperty);
        Assert.assertEquals(((ExtendedStatistics)netGearCommunicator.getMultipleStatistics().get(0)).getStatistics().get("Port Controls#Port 1/0/1"), "false");
    }

    @Test
    @Ignore
    public void controlPropertyReload() throws Exception {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Reload");
        netGearCommunicator.controlProperty(controllableProperty);
    }

}
