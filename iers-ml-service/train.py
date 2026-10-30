"""Train the crash-severity model and save it to app/model/artifacts/.

Run:  python train.py
"""
from app.model.trainer import train_model

if __name__ == "__main__":
    train_model()
