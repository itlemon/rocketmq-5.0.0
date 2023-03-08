/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.broker;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.common.BrokerConfig;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.common.RemotingUtil;
import org.apache.rocketmq.remoting.common.TlsMode;
import org.apache.rocketmq.remoting.netty.NettyClientConfig;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.netty.TlsSystemConfig;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.srvutil.ServerUtil;
import org.apache.rocketmq.store.config.BrokerRole;
import org.apache.rocketmq.store.config.MessageStoreConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.rocketmq.remoting.netty.TlsSystemConfig.TLS_ENABLE;

public class BrokerStartup {
    public static Properties properties = null;
    public static CommandLine commandLine = null;
    public static String configFile = null;
    public static InternalLogger log;
    public static final SystemConfigFileHelper CONFIG_FILE_HELPER = new SystemConfigFileHelper();

    public static void main(String[] args) {
        start(createBrokerController(args));
    }

    public static BrokerController start(BrokerController controller) {
        try {

            // 启动controller
            controller.start();

            // 配置日志信息并输出到控制台
            String tip = "The broker[" + controller.getBrokerConfig().getBrokerName() + ", "
                + controller.getBrokerAddr() + "] boot success. serializeType=" + RemotingCommand.getSerializeTypeConfigInThisServer();

            if (null != controller.getBrokerConfig().getNamesrvAddr()) {
                tip += " and name server is " + controller.getBrokerConfig().getNamesrvAddr();
            }

            log.info(tip);
            System.out.printf("%s%n", tip);
            return controller;
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        }

        return null;
    }

    public static void shutdown(final BrokerController controller) {
        if (null != controller) {
            controller.shutdown();
        }
    }

    public static BrokerController createBrokerController(String[] args) {
        // 设置当前MQ版本设置为全局环境变量，方便在本项目的任何地方进行获取
        // key为rocketmq.remoting.version，当前版本值为：Version.V5_0_0，数值为413
        System.setProperty(RemotingCommand.REMOTING_VERSION_KEY, Integer.toString(MQVersion.CURRENT_VERSION));

        try {
            // 构建-h 和 -n 的命令行选项option，并将两个命令行选项加入到options中
            // 发散一下，其实可以在buildCommandlineOptions加一些自定义代码
            Options options = ServerUtil.buildCommandlineOptions(new Options());

            // 从命令行参数中解析各个命令行选项，将选项名和选项值加载到CommandLine对象中，
            // 其中本类的buildCommandlineOptions方法，向options中加入了三个选项，分别是configFile、printConfigItem以及printImportantConfig
            // 在解析命令行选项的时候，如果发现命令行选项中包含-h或者--help，那么NameServer是不会启动的，只会打印命令行帮助信息，打印结果如下所示：
            // usage: mqnamesrv [-c <arg>] [-h] [-n <arg>] [-p]
            // -c,--configFile <arg>    Name server config properties file
            // -h,--help                Print help
            // -n,--namesrvAddr <arg>   Name server address list, eg: '192.168.0.1:9876;192.168.0.2:9876'
            // -p,--printConfigItem     Print all config items
            // 如果你自定义了一些必需的命令行选项，但是在启动的时候，又没有填写这些选项，那么是会解析出错，出错后，也会打印出各个选项的信息
            commandLine = ServerUtil.parseCmdLine("mqbroker", args, buildCommandlineOptions(options),
                new DefaultParser());
            if (null == commandLine) {
                System.exit(-1);
            }

            // 创建配置对象
            final BrokerConfig brokerConfig = new BrokerConfig();
            final NettyServerConfig nettyServerConfig = new NettyServerConfig();
            final NettyClientConfig nettyClientConfig = new NettyClientConfig();

            // 设置一下是否使用SSL
            nettyClientConfig.setUseTLS(Boolean.parseBoolean(System.getProperty(TLS_ENABLE,
                String.valueOf(TlsSystemConfig.tlsMode == TlsMode.ENFORCING))));

            // 这里默认启动监听的端口是10911，当在同一个机器上启动多个broker实例的时候，需要在配置配置文件中配置不同的端口，否则后启动的实例讲无法启动
            nettyServerConfig.setListenPort(10911);
            final MessageStoreConfig messageStoreConfig = new MessageStoreConfig();

            // 在某些情况下，Consumer需要将消费位点重置到1-2天前，这时在内存有限的Master
            // Broker上，CommitLog会承载比较重的IO压力，影响到该Broker的其它消息的读与写。可以开启slaveReadEnable=true ，当Master
            // Broker发现Consumer的消费位点与CommitLog的最新值的差值的容量超过该机器内存的百分比（ accessMessageInMemoryMaxRatio=40%），
            // 会推荐Consumer从Slave Broker中去读取数据，降低Master Broker的IO。
            if (BrokerRole.SLAVE == messageStoreConfig.getBrokerRole()) {
                // 如果当前是Broker实例是SLAVE，那么将这个比例设置为30%
                int ratio = messageStoreConfig.getAccessMessageInMemoryMaxRatio() - 10;
                messageStoreConfig.setAccessMessageInMemoryMaxRatio(ratio);
            }

            // 如果在命令行参数中指定了-c或者--configFile，那么将解析后面的配置文件中的配置到properties中
            if (commandLine.hasOption('c')) {
                String file = commandLine.getOptionValue('c');
                if (file != null) {
                    CONFIG_FILE_HELPER.setFile(file);
                    configFile = file;
                    BrokerPathConfigHelper.setBrokerConfigPath(file);
                    properties = CONFIG_FILE_HELPER.loadConfig();
                }
            }

            // 将解析出来的配置设置到各个配置对象中，配置中如果指定了监听端口，那么将覆盖上面设置的10911
            if (properties != null) {
                properties2SystemEnv(properties);
                MixAll.properties2Object(properties, brokerConfig);
                MixAll.properties2Object(properties, nettyServerConfig);
                MixAll.properties2Object(properties, nettyClientConfig);
                MixAll.properties2Object(properties, messageStoreConfig);
            }

            // 这里再解析一遍命令行参数，装配brokerConfig，从代码执行顺序来看，
            // 命令行中的参数优先级要高于配置文件中的配置，因为这里可以覆盖配置文件中的值
            MixAll.properties2Object(ServerUtil.commandLine2Properties(commandLine), brokerConfig);

            // 如果没有配置系统属性值rocketmq.home.dir或者环境变量ROCKETMQ_HOME，那么将直接退出
            // rocketmq_home默认来源于配置rocketmq.home.dir，如果没有配置，将从环境变量中获取ROCKETMQ_HOME参数
            if (null == brokerConfig.getRocketmqHome()) {
                System.out.printf("Please set the %s variable in your environment to match the location of the RocketMQ installation", MixAll.ROCKETMQ_HOME_ENV);
                System.exit(-2);
            }

            // 通过try catch来校验namesrvAddr是否满足规则
            String namesrvAddr = brokerConfig.getNamesrvAddr();
            if (null != namesrvAddr) {
                try {
                    String[] addrArray = namesrvAddr.split(";");
                    for (String addr : addrArray) {
                        RemotingUtil.string2SocketAddress(addr);
                    }
                } catch (Exception e) {
                    System.out.printf(
                        "The Name Server Address[%s] illegal, please set it as follows, \"127.0.0.1:9876;192.168.0.1:9876\"%n",
                        namesrvAddr);
                    System.exit(-3);
                }
            }

            // 如果不支持controller模式，那么需要正确配置Master和slave的ID，其中masterId的是0，slaveId必须大于0
            // 如果支持controller模式，后续broker将支持自动换主
            if (!brokerConfig.isEnableControllerMode()) {
                switch (messageStoreConfig.getBrokerRole()) {
                    case ASYNC_MASTER:
                    case SYNC_MASTER:
                        brokerConfig.setBrokerId(MixAll.MASTER_ID);
                        break;
                    case SLAVE:
                        if (brokerConfig.getBrokerId() <= 0) {
                            System.out.printf("Slave's brokerId must be > 0");
                            System.exit(-3);
                        }

                        break;
                    default:
                        break;
                }
            }

            if (messageStoreConfig.isEnableDLegerCommitLog()) {
                brokerConfig.setBrokerId(-1);
            }

            // 设置高可用端口，端口是前面设置的监听端口数+1
            messageStoreConfig.setHaListenPort(nettyServerConfig.getListenPort() + 1);
            LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(lc);
            lc.reset();
            System.setProperty("brokerLogDir", "");
            if (brokerConfig.isIsolateLogEnable()) {
                System.setProperty("brokerLogDir", brokerConfig.getBrokerName() + "_" + brokerConfig.getBrokerId());
            }
            if (brokerConfig.isIsolateLogEnable() && messageStoreConfig.isEnableDLegerCommitLog()) {
                System.setProperty("brokerLogDir", brokerConfig.getBrokerName() + "_" + messageStoreConfig.getdLegerSelfId());
            }
            configurator.doConfigure(brokerConfig.getRocketmqHome() + "/conf/logback_broker.xml");

            // 如果在启动参数加上选项-p或者-m，那么将打印出全部配置或者重要配置
            // 配置打印结束后就退出进程
            if (commandLine.hasOption('p')) {
                InternalLogger console = InternalLoggerFactory.getLogger(LoggerName.BROKER_CONSOLE_NAME);
                MixAll.printObjectProperties(console, brokerConfig);
                MixAll.printObjectProperties(console, nettyServerConfig);
                MixAll.printObjectProperties(console, nettyClientConfig);
                MixAll.printObjectProperties(console, messageStoreConfig);
                System.exit(0);
            } else if (commandLine.hasOption('m')) {
                InternalLogger console = InternalLoggerFactory.getLogger(LoggerName.BROKER_CONSOLE_NAME);
                MixAll.printObjectProperties(console, brokerConfig, true);
                MixAll.printObjectProperties(console, nettyServerConfig, true);
                MixAll.printObjectProperties(console, nettyClientConfig, true);
                MixAll.printObjectProperties(console, messageStoreConfig, true);
                System.exit(0);
            }

            log = InternalLoggerFactory.getLogger(LoggerName.BROKER_LOGGER_NAME);
            MixAll.printObjectProperties(log, brokerConfig);
            MixAll.printObjectProperties(log, nettyServerConfig);
            MixAll.printObjectProperties(log, nettyClientConfig);
            MixAll.printObjectProperties(log, messageStoreConfig);

            brokerConfig.setInBrokerContainer(false);

            // 创建 BrokerController 对象
            final BrokerController controller = new BrokerController(
                brokerConfig,
                nettyServerConfig,
                nettyClientConfig,
                messageStoreConfig);
            // remember all configs to prevent discard
            controller.getConfiguration().registerConfig(properties);

            // controller初始化
            boolean initResult = controller.initialize();
            if (!initResult) {
                controller.shutdown();
                System.exit(-3);
            }

            // 注册进程退出的钩子函数，进程退出的时候，将调用该线程去完成一些资源关闭的操作
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                private volatile boolean hasShutdown = false;
                private AtomicInteger shutdownTimes = new AtomicInteger(0);

                @Override
                public void run() {
                    synchronized (this) {
                        log.info("Shutdown hook was invoked, {}", this.shutdownTimes.incrementAndGet());
                        if (!this.hasShutdown) {
                            this.hasShutdown = true;
                            long beginTime = System.currentTimeMillis();
                            controller.shutdown();
                            long consumingTimeTotal = System.currentTimeMillis() - beginTime;
                            log.info("Shutdown hook over, consuming total time(ms): {}", consumingTimeTotal);
                        }
                    }
                }
            }, "ShutdownHook"));

            return controller;
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        }

        return null;
    }

    private static void properties2SystemEnv(Properties properties) {
        if (properties == null) {
            return;
        }
        String rmqAddressServerDomain = properties.getProperty("rmqAddressServerDomain", MixAll.WS_DOMAIN_NAME);
        String rmqAddressServerSubGroup = properties.getProperty("rmqAddressServerSubGroup", MixAll.WS_DOMAIN_SUBGROUP);
        System.setProperty("rocketmq.namesrv.domain", rmqAddressServerDomain);
        System.setProperty("rocketmq.namesrv.domain.subgroup", rmqAddressServerSubGroup);
    }

    private static Options buildCommandlineOptions(final Options options) {
        Option opt = new Option("c", "configFile", true, "Broker config properties file");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("p", "printConfigItem", false, "Print all config item");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("m", "printImportantConfig", false, "Print important config item");
        opt.setRequired(false);
        options.addOption(opt);

        return options;
    }

    public static class SystemConfigFileHelper {
        private static final Logger LOGGER = LoggerFactory.getLogger(SystemConfigFileHelper.class);

        private String file;

        public SystemConfigFileHelper() {
        }

        public Properties loadConfig() throws Exception {
            InputStream in = new BufferedInputStream(new FileInputStream(file));
            Properties properties = new Properties();
            properties.load(in);
            in.close();
            return properties;
        }

        public void update(Properties properties) throws Exception {
            LOGGER.error("[SystemConfigFileHelper] update no thing.");
        }

        public void setFile(String file) {
            this.file = file;
        }

        public String getFile() {
            return file;
        }
    }
}
