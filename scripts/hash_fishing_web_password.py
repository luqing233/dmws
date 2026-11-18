"""Generate a PBKDF2 credential for dmws.fishing-web.admin-password-hash; never prints the password."""
import base64
import getpass
import hashlib
import secrets


def encode(password, salt=None, iterations=310000):
    if not 12 <= len(password) <= 256:
        raise ValueError("密码长度应为12～256个字符")
    if not 100000 <= iterations <= 2000000:
        raise ValueError("迭代次数无效")
    salt = secrets.token_bytes(16) if salt is None else salt
    if len(salt) != 16:
        raise ValueError("盐值必须为16字节")
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    b64 = lambda value: base64.urlsafe_b64encode(value).decode().rstrip("=")
    return f"pbkdf2-sha256${iterations}${b64(salt)}${b64(digest)}"


if __name__ == "__main__":
    password = getpass.getpass("审核管理员密码（至少12字符）：")
    if password != getpass.getpass("再次输入："):
        raise SystemExit("两次密码不一致，未生成配置")
    try:
        print(encode(password))
    except ValueError as error:
        raise SystemExit(str(error))
