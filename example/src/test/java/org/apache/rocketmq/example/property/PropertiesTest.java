package org.apache.rocketmq.example.property;

import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.junit.Test;

/**
 * @author itlemon <lemon_jiang@aliyun.com>
 * Created on 2023-02-15
 */
public class PropertiesTest {

    @Test
    public void testSystemProperties() {
        System.out.println(System.getProperty("user.home"));
        System.setProperty(RemotingCommand.REMOTING_VERSION_KEY, Integer.toString(MQVersion.CURRENT_VERSION));
        System.out.println(System.getProperty(RemotingCommand.REMOTING_VERSION_KEY));
    }

}
