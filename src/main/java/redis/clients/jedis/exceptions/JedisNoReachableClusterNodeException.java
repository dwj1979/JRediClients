package redis.clients.jedis.exceptions;

public class JedisNoReachableClusterNodeException extends JedisConnectionException {
	private static final long serialVersionUID = 1L;

	public JedisNoReachableClusterNodeException(String message) {
		super(message);
	}

	public JedisNoReachableClusterNodeException(Throwable cause) {
		super(cause);
	}

	public JedisNoReachableClusterNodeException(String message, Throwable cause) {
		super(message, cause);
	}
}