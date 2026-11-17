from __future__ import annotations

import io
import os
import re
from datetime import date, timedelta
from typing import Any, Optional

from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

app = FastAPI(title="Corvo AI Service", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

SERVICE_TOKEN = os.getenv("CORVO_AI_TOKEN", "")
CATEGORIES = [
    "Bearings & Transmission",
    "Capital Equipment",
    "Electrical Spares",
    "Facility Services",
    "Fasteners",
    "Hydraulics & Pneumatics",
    "Lubricants",
    "Office & IT",
    "Packaging",
    "Safety & PPE",
    "Steel & Metals",
    "Testing & Calibration",
    "Welding Consumables",
]

MONTHS = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}
ISO_DATE = re.compile(r"\b(\d{4})-(\d{2})-(\d{2})\b")
DAY_MONTH = re.compile(
    r"(?i)\b(\d{1,2})\s*(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?\s*(\d{4})?"
)
MONTH_DAY = re.compile(
    r"(?i)\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?\s+(\d{1,2})(?:,?\s*(\d{4}))?"
)
QTY_UOM = re.compile(
    r"(?i)\b(\d[\d,]*(?:\.\d+)?)\s*(nos|pcs|pieces?|ltr|litres?|liters?|kg|mtr|meters?|metres?|job|jobs|box|boxes|cyl|cylinders?|set|sets|roll|rolls|pack|packs|pkt|packets?|ton|tons|mt)\b"
)
NUMBER = re.compile(r"\b(\d[\d,]*(?:\.\d+)?)\b")


def require_token(authorization: Optional[str] = Header(default=None)) -> None:
    if not SERVICE_TOKEN:
        return
    if not authorization or authorization != f"Bearer {SERVICE_TOKEN}":
        raise HTTPException(status_code=401, detail="invalid service token")


def number(raw: str) -> float:
    return float(raw.replace(",", ""))


def money(raw: str) -> float:
    return round(number(raw), 2)


def parse_date(text: str, today: date, future: bool) -> Optional[date]:
    iso = ISO_DATE.search(text)
    if iso:
        return date(int(iso.group(1)), int(iso.group(2)), int(iso.group(3)))
    dm = DAY_MONTH.search(text)
    if dm:
        month = MONTHS[dm.group(2).lower()[:3]]
        day = int(dm.group(1))
        year = int(dm.group(3)) if dm.group(3) else default_year(today, month, day, future)
        return safe_date(year, month, day)
    md = MONTH_DAY.search(text)
    if md:
        month = MONTHS[md.group(1).lower()[:3]]
        day = int(md.group(2))
        year = int(md.group(3)) if md.group(3) else default_year(today, month, day, future)
        return safe_date(year, month, day)
    lower = text.lower()
    if "today" in lower:
        return today
    if "tomorrow" in lower:
        return today + timedelta(days=1)
    return None


def default_year(today: date, month: int, day: int, future: bool) -> int:
    candidate = safe_date(today.year, month, day)
    if candidate is None:
        return today.year
    if future and candidate < today:
        return today.year + 1
    return today.year


def safe_date(year: int, month: int, day: int) -> Optional[date]:
    try:
        return date(year, month, day)
    except ValueError:
        return None


def detect_plant(lower: str) -> Optional[str]:
    if re.search(r"\bpun1\b", lower) or "pune" in lower or "bhosari" in lower:
        return "PUN1"
    if re.search(r"\bchk1\b", lower) or "chakan" in lower:
        return "CHK1"
    return None


def detect_policy_parameter(lower: str) -> Optional[str]:
    if "emergency" in lower:
        return "emergency_limit"
    if "spot" in lower:
        return "spot_limit"
    if "rfq" in lower or "boundary" in lower:
        return "rfq_rfp_boundary"
    if "club" in lower:
        return "club_window_days"
    if "need by" in lower or "need-by" in lower:
        return "need_by_window_days"
    if "consolidation" in lower:
        return "consolidation_hours"
    return None


def detect_value(lower: str) -> Optional[float]:
    match = re.search(
        r"(?i)(?:limit|boundary|window|consolidation|hours|days)\s*(?:is|to|at|of|=|:)?\s*(?:₹|rs\.?|inr)?\s*([0-9][0-9,]*(?:\.[0-9]+)?)\s*(lakh|lac|crore|k)?",
        lower,
    )
    if match:
        return scale_value(match.group(1), match.group(2))
    match = re.search(
        r"(?i)(?:₹|rs\.?|inr)\s*([0-9][0-9,]*(?:\.[0-9]+)?)\s*(lakh|lac|crore|k)?", lower
    )
    if match:
        return scale_value(match.group(1), match.group(2))
    return None


def scale_value(raw: str, unit: Optional[str]) -> float:
    value = number(raw)
    if not unit:
        return value
    unit = unit.lower()
    if unit in ("lakh", "lac"):
        return value * 100000
    if unit == "crore":
        return value * 10000000
    if unit == "k":
        return value * 1000
    return value


def detect_scope(lower: str) -> dict[str, Any]:
    scope: dict[str, Any] = {}
    code = re.search(r"(?i)\bitm-\d{4}\b", lower)
    if code:
        return {"item_code": code.group(0).upper()}
    plant = detect_plant(lower)
    if plant:
        scope["plant"] = plant
    category = None
    for candidate in CATEGORIES:
        if candidate.lower() in lower and (category is None or len(candidate) > len(category)):
            category = candidate
    if category:
        scope["category"] = category
    return scope


def describe_policy(text: str, effective_from: Optional[str], today: Optional[str]) -> dict[str, Any]:
    today_date = date.fromisoformat(today) if today else date.today()
    lower = text.lower()
    warnings: list[str] = []
    parsed_date = parse_date(text, today_date, future=True)
    if effective_from:
        parsed_date = date.fromisoformat(effective_from)
    if parsed_date is None:
        warnings.append("Effective date is missing")

    operations: list[dict[str, Any]] = []
    parameter = detect_policy_parameter(lower)
    value = detect_value(lower)
    scope = detect_scope(lower)
    if parameter and value is not None:
        operation: dict[str, Any] = {"type": "set_parameter", "key": parameter, "value": value}
        if scope:
            operation["scope_json"] = scope
        operations.append(operation)
    elif not parameter:
        warnings.append("No supported limit or parameter was detected")

    # Full policy documents list the baseline limits in clauses 5.3-5.5.
    clause_limits: dict[str, float] = {}
    for line in text.splitlines():
        clause = re.search(r"(?i)\b5\.([345])\b.*?(?:₹|rs\.?|inr)\s*([\d,]+)", line)
        if clause:
            key = {"3": "emergency_limit", "4": "spot_limit", "5": "rfq_rfp_boundary"}[clause.group(1)]
            clause_limits[key] = money(clause.group(2))
    if len(clause_limits) >= 2:
        operations = [
            {"type": "set_parameter", "key": key, "value": value}
            for key, value in clause_limits.items()
        ]
        scope = {}

    scope_text = ""
    if scope:
        scope_text = " for " + ", ".join(f"{k}={v}" for k, v in scope.items())
    if operations:
        summary = (
            f"From {parsed_date.isoformat() if parsed_date else 'the effective date'}, "
            f"{operations[0]['key']} becomes {operations[0]['value']:,.0f}{scope_text}"
        )
    else:
        summary = "No policy change could be extracted; use the manual operations editor."

    return {
        "effective_from": parsed_date.isoformat() if parsed_date else None,
        "summary": summary,
        "operations": operations,
        "warnings": warnings,
        "confidence": 0.9 if operations and parsed_date else 0.5 if operations else 0.0,
        "source": "deterministic-parser",
    }


def pdf_text(data: bytes) -> str:
    try:
        from pypdf import PdfReader

        reader = PdfReader(io.BytesIO(data))
        return "\n".join(page.extract_text() or "" for page in reader.pages)
    except Exception:
        try:
            return data.decode("utf-8", errors="ignore")
        except Exception:
            return ""


def extract_contract(text: str) -> dict[str, Any]:
    warnings: list[str] = []
    contract_match = re.search(r"Contract No\.?\s*([A-Z0-9-]+)", text)
    supplier_match = re.search(r"and\s+(.+?),\s*.*?\(Supplier\s+ID\s+([A-Za-z0-9]+)\)", text, re.S)
    term_match = re.search(
        r"valid from\s+(\d{4}-\d{2}-\d{2})\s+to\s+(\d{4}-\d{2}-\d{2})", text
    )
    ceiling_match = re.search(r"exceed\s+INR\s+([\d,]+)", text)

    lines: list[dict[str, Any]] = []
    for raw in text.splitlines():
        line = re.search(
            r"(?i)^\s*\d+\s+(ITM-\d{4})\s+(.+?)\s+([A-Z][A-Z0-9]{1,5})\s+([\d,]+(?:\.\d+)?)\s*$",
            raw.strip(),
        )
        if line:
            lines.append(
                {
                    "item_code": line.group(1),
                    "description": line.group(2).strip(),
                    "uom": line.group(3).upper(),
                    "rate": money(line.group(4)),
                }
            )

    if not contract_match:
        warnings.append("Contract ID not found")
    if not supplier_match:
        warnings.append("Supplier not found")
    if not term_match:
        warnings.append("Validity dates not found")
    if not ceiling_match:
        warnings.append("Value ceiling not found")
    if not lines:
        warnings.append("Schedule A lines not found")

    return {
        "contract_id": contract_match.group(1) if contract_match else None,
        "supplier_id": supplier_match.group(2) if supplier_match else None,
        "supplier_name": supplier_match.group(1).strip() if supplier_match else None,
        "start_date": term_match.group(1) if term_match else None,
        "end_date": term_match.group(2) if term_match else None,
        "value_cap": money(ceiling_match.group(1)) if ceiling_match else None,
        "lines": lines,
        "warnings": warnings,
        "confidence": 0.95 if not warnings else 0.6,
        "source": "pdf" if text.lstrip().startswith("RATE CONTRACT") else "text",
        "status": "Active",
    }


def parse_chat(text: str, today: Optional[str]) -> dict[str, Any]:
    today_date = date.fromisoformat(today) if today else date.today()
    lower = text.lower()
    intent = "POLICY" if ("limit" in lower or "policy" in lower) else "PR"
    quantity_match = QTY_UOM.search(text)
    quantity = number(quantity_match.group(1)) if quantity_match else None
    if quantity is None:
        for match in NUMBER.finditer(text):
            value = number(match.group(1))
            if not (1000 <= value <= 2100 and value.is_integer()):
                quantity = value
                break
    uom = quantity_match.group(2).upper() if quantity_match else None
    needs = {
        "item_description": text if intent == "PR" else None,
        "quantity": quantity,
        "uom": uom,
        "plant": detect_plant(lower),
        "need_by_date": (parse_date(text, today_date, future=False).isoformat()
                         if parse_date(text, today_date, future=False) else None),
        "urgency": "Emergency" if any(w in lower for w in ("emergency", "urgent", "asap")) else "Normal",
    }
    return {
        "intent": intent,
        "slots": needs,
        "warnings": [],
        "confidence": 0.6,
        "tools": ["resolve_item", "validate_plant"],
    }


class PolicyRequest(BaseModel):
    text: str
    effective_from: Optional[str] = None
    today: Optional[str] = None


class ChatRequest(BaseModel):
    message: str
    today: Optional[str] = None


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "corvo-ai"}


@app.post("/policy/describe", dependencies=[Depends(require_token)])
def policy_describe(request: PolicyRequest) -> dict[str, Any]:
    return describe_policy(request.text, request.effective_from, request.today)


@app.post("/policy/extract", dependencies=[Depends(require_token)])
def policy_extract(request: PolicyRequest) -> dict[str, Any]:
    return describe_policy(request.text, request.effective_from, request.today)


@app.post("/policy/upload", dependencies=[Depends(require_token)])
async def policy_upload(
    file: UploadFile = File(...),
    today: Optional[str] = None,
    effective_from: Optional[str] = None,
) -> dict[str, Any]:
    data = await file.read()
    text = pdf_text(data)
    result = describe_policy(text, effective_from, today)
    result["document_ref"] = file.filename
    if not text.strip():
        result["warnings"].append("The PDF is image-only; use the manual policy editor")
    return result


@app.post("/contract/extract", dependencies=[Depends(require_token)])
async def contract_extract(file: UploadFile = File(...)) -> dict[str, Any]:
    data = await file.read()
    text = pdf_text(data)
    result = extract_contract(text)
    result["document_ref"] = file.filename
    return result


@app.post("/chat/parse", dependencies=[Depends(require_token)])
def chat_parse(request: ChatRequest) -> dict[str, Any]:
    return parse_chat(request.message, request.today)
