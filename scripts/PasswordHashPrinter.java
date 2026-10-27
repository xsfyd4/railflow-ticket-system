import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 本地生成演示账号 BCrypt 密文的小工具。
 *
 * <p>用法：
 * <pre>
 * java -cp "spring-security-crypto.jar;commons-logging.jar" \
 *      scripts/PasswordHashPrinter.java 123456
 * </pre>
 */
public class PasswordHashPrinter {

    public static void main(String[] args) {
        if (args.length == 3 && "--verify".equals(args[0])) {
            System.out.println(new BCryptPasswordEncoder()
                    .matches(args[1], args[2]));
            return;
        }
        String raw = args.length == 0 ? "123456" : args[0];
        System.out.println(new BCryptPasswordEncoder().encode(raw));
    }
}
