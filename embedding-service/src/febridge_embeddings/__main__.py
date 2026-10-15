import os

import uvicorn
from dotenv import load_dotenv


def main():
    load_dotenv()
    uvicorn.run(
        "febridge_embeddings.app:create_app",
        factory=True,
        host=os.getenv("EMBEDDING_HOST", "127.0.0.1"),
        port=int(os.getenv("EMBEDDING_PORT", "8091")),
        workers=1,
    )


if __name__ == "__main__":
    main()
