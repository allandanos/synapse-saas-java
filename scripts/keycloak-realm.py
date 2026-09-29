#!/usr/bin/env python3
"""Copy the reference's dev realm, adding this port's redirect URIs.

The shipped `infrastructure/keycloak/realm-dev.json` only knows the reference's
own ports (8000 for the API, 3000 for the console). The reference repo is never
modified, so the e2e recipe copies the realm and appends the origins this port
actually runs on before importing it into its own Keycloak container.

    keycloak-realm.py <source realm> <target realm> <origin>...
"""

import json
import sys

CLIENT_ID = "synapse-web"


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    source, target, *origins = argv
    with open(source, encoding="utf-8") as handle:
        realm = json.load(handle)
    for client in realm.get("clients", []):
        if client.get("clientId") != CLIENT_ID:
            continue
        for origin in origins:
            redirect = f"{origin}/*"
            if redirect not in client.setdefault("redirectUris", []):
                client["redirectUris"].append(redirect)
            if origin not in client.setdefault("webOrigins", []):
                client["webOrigins"].append(origin)
    with open(target, "w", encoding="utf-8") as handle:
        json.dump(realm, handle, indent=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
