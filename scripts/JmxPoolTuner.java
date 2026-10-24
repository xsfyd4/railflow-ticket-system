import javax.management.Attribute;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.util.Set;

/** 本地压测辅助工具：查看并动态修改 HikariCP 最大连接数。 */
public final class JmxPoolTuner {

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException(
                    "用法：JmxPoolTuner <jmx-url> [maximumPoolSize]");
        }

        JMXServiceURL url = new JMXServiceURL(args[0]);
        try (JMXConnector connector = JMXConnectorFactory.connect(url)) {
            MBeanServerConnection server = connector.getMBeanServerConnection();
            Set<ObjectName> names = server.queryNames(
                    new ObjectName("com.zaxxer.hikari:*"), null);
            for (ObjectName name : names) {
                System.out.println("MBean: " + name);
                for (MBeanAttributeInfo attribute
                        : server.getMBeanInfo(name).getAttributes()) {
                    if (attribute.isReadable()) {
                        try {
                            System.out.println(
                                    "  " + attribute.getName() + " = "
                                            + server.getAttribute(
                                            name, attribute.getName()));
                        } catch (Exception ignored) {
                            // 部分运行指标在空闲时可能无法读取，忽略即可。
                        }
                    }
                }

                if (args.length == 2) {
                    try {
                        server.setAttribute(name,
                                new Attribute("maximumPoolSize",
                                        Integer.parseInt(args[1])));
                        System.out.println("已设置 maximumPoolSize=" + args[1]);
                    } catch (Exception ignored) {
                        // 只有 HikariConfig MBean 暴露可写的 maximumPoolSize。
                    }
                }
            }
        }
    }

    private JmxPoolTuner() {
    }
}
