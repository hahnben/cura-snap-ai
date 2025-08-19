# Security Guidelines - CuraSnap AI Backend

## Redis Security Configuration

### Environment Variables Required for Production

```bash
# REQUIRED in production - do not use defaults
export REDIS_PASSWORD="your-strong-redis-password-here"
export REDIS_HOST="your-redis-host"
export REDIS_PORT="6379"
export REDIS_DATABASE="0"
```

### Security Measures Implemented

#### 1. Redis Authentication
- Redis requires password authentication (`requirepass`)
- Default password is set but MUST be overridden in production
- Connection strings include authentication

#### 2. Network Security
- Redis port bound to localhost only (`127.0.0.1:6379`)
- Not exposed to external networks
- Docker containers communicate via internal network

#### 3. Resource Limits
- Memory limit: 512MB per Redis container
- CPU limit: 0.5 cores
- Memory policy: `allkeys-lru` for automatic eviction

#### 4. Serialization Security
- Disabled Jackson `DefaultTyping` to prevent RCE attacks
- Uses secure JSON serialization without type information
- String keys only for Redis operations

#### 5. Information Disclosure Prevention
- Health endpoints do not expose sensitive error details
- Exception messages are sanitized in API responses
- Stack traces are logged but not returned to clients

#### 6. Actuator Security
- Health endpoints require authorization for details
- Limited endpoint exposure (`health` only)
- Sensitive information requires authentication

### Security Best Practices

#### Production Deployment
1. **Always use environment variables** for Redis credentials
2. **Enable Redis AUTH** with strong passwords (32+ characters)
3. **Use TLS/SSL** for Redis connections in production
4. **Implement network segmentation** (VPC, firewalls)
5. **Enable Redis logging** for security monitoring
6. **Regular security updates** for Redis and dependencies

#### Development Security
1. **Never commit passwords** to version control
2. **Use local Redis instances** only for development
3. **Different credentials** for each environment
4. **Regular dependency scans** with `mvn dependency-check:check`

### Security Testing

```bash
# Run security-focused tests
./mvnw test -Dtest="*Security*,*Health*"

# Check for vulnerable dependencies
./mvnw org.owasp:dependency-check-maven:check

# Test Redis authentication
redis-cli -a $REDIS_PASSWORD ping
```

### Incident Response

If Redis security is compromised:
1. Immediately change Redis password
2. Restart all Redis connections
3. Check logs for unauthorized access
4. Review and rotate application secrets
5. Update firewall rules if needed

### Security Monitoring

Monitor these metrics:
- Failed Redis authentication attempts
- Unusual Redis connection patterns
- High memory usage (potential DoS)
- Health endpoint access logs
- Exception rates in Redis operations

## Contact

For security concerns: security@curasnap.ai