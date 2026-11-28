"""HTTP helpers shared by the ZCode client and the OAuth flow.

`urllib` follows 301/302/307/308 by default and **keeps the request headers**,
including ``Authorization``. Both the ZCode API client and the OAuth poll send a
Bearer JWT, so a redirect (a compromised or spoofed response, or a MITM with a
valid certificate) would hand the token to whatever host the ``Location`` names.
The Electron side already refuses redirects for authorized requests
(``desktop/src/main/zcode-auth.js``); this module gives the Python side the same
guarantee, so the two implementations cannot drift apart again.
"""

from __future__ import annotations

import urllib.error
import urllib.request

REDIRECT_STATUSES = (301, 302, 303, 307, 308)


class _RefuseAuthorizedRedirect(urllib.request.HTTPRedirectHandler):
    """Fail instead of following a redirect on a request with credentials."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D102
        if req.has_header("Authorization") or req.has_header("authorization"):
            raise urllib.error.HTTPError(
                req.full_url,
                code,
                "Refusing to follow a redirect for a request that carries credentials",
                headers,
                fp,
            )
        return super().redirect_request(req, fp, code, msg, headers, newurl)


_AUTHORIZED_OPENER = urllib.request.build_opener(_RefuseAuthorizedRedirect())


def _carries_credentials(request: urllib.request.Request) -> bool:
    return request.has_header("Authorization") or request.has_header("authorization")


def urlopen(request: urllib.request.Request, timeout: float):
    """Open ``request`` like :func:`urllib.request.urlopen`, minus the leak.

    Anonymous requests keep the stock behaviour (and stay monkeypatchable, which
    the client tests rely on). Authorized requests go through an opener whose
    redirect handler refuses to follow: the caller sees
    :class:`urllib.error.HTTPError` with the original redirect status, exactly
    like any other HTTP error.
    """
    if _carries_credentials(request):
        return _AUTHORIZED_OPENER.open(request, timeout=timeout)
    return urllib.request.urlopen(request, timeout=timeout)


__all__ = ["REDIRECT_STATUSES", "urlopen"]
