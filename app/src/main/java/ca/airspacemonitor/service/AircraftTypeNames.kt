package ca.airspacemonitor.service

/**
 * Spoken (TTS) names for common ICAO type designators. The feed's raw `t`
 * field ("B738", "C172") reads poorly letter-by-letter, so announcements
 * prefer the mapped full name and fall back to the callsign when a designator
 * is missing or not in this dictionary. Keep entries TTS-friendly: plain words,
 * digits are fine, no slashes or abbreviations.
 */
object AircraftTypeNames {

    private val NAMES = mapOf(
        // Airbus
        "A318" to "Airbus A318", "A319" to "Airbus A319", "A320" to "Airbus A320", "A321" to "Airbus A321",
        "A332" to "Airbus A330", "A333" to "Airbus A330", "A339" to "Airbus A330",
        "A342" to "Airbus A340", "A343" to "Airbus A340", "A345" to "Airbus A340", "A346" to "Airbus A340",
        "A359" to "Airbus A350", "A35K" to "Airbus A350", "A388" to "Airbus A380",
        // Boeing
        "B733" to "Boeing 737", "B736" to "Boeing 737", "B737" to "Boeing 737", "B738" to "Boeing 737",
        "B739" to "Boeing 737", "B38M" to "Boeing 737 MAX", "B39M" to "Boeing 737 MAX",
        "B742" to "Boeing 747", "B743" to "Boeing 747", "B744" to "Boeing 747", "B748" to "Boeing 747",
        "B752" to "Boeing 757", "B753" to "Boeing 757",
        "B762" to "Boeing 767", "B763" to "Boeing 767", "B764" to "Boeing 767",
        "B772" to "Boeing 777", "B773" to "Boeing 777", "B77L" to "Boeing 777", "B77W" to "Boeing 777",
        "B778" to "Boeing 777", "B779" to "Boeing 777",
        "B788" to "Boeing 787", "B789" to "Boeing 787", "B78X" to "Boeing 787",
        // Other airliners / regional
        "MD11" to "McDonnell Douglas MD 11",
        "CL65" to "Canadair Regional Jet", "CRJ2" to "Canadair Regional Jet", "CRJ7" to "Canadair Regional Jet",
        "CRJ9" to "Canadair Regional Jet", "CRJX" to "Canadair Regional Jet",
        "E135" to "Embraer 135", "E145" to "Embraer 145", "E170" to "Embraer 170",
        "E175" to "Embraer 175", "E75L" to "Embraer 175", "E75S" to "Embraer 170",
        "E190" to "Embraer 190", "E195" to "Embraer 195", "E120" to "Embraer 120 Brasilia",
        "AT45" to "ATR 42", "AT43" to "ATR 42", "AT72" to "ATR 72", "AT76" to "ATR 72",
        "SF34" to "Saab 340", "SB20" to "Saab 2000", "D328" to "Dornier 328", "B190" to "Beechcraft 1900",
        // De Havilland
        "DHC1" to "De Havilland Chipmunk", "DHC2" to "De Havilland Beaver", "DHC3" to "De Havilland Otter",
        "DHC6" to "De Havilland Twin Otter", "DHC8" to "De Havilland Dash 8",
        "DH8A" to "De Havilland Dash 8", "DH8C" to "De Havilland Dash 8", "DH8D" to "De Havilland Dash 8",
        "JS31" to "Jetstream 31", "JS41" to "Jetstream 41",
        // Cessna
        "C150" to "Cessna 150", "C152" to "Cessna 152", "C172" to "Cessna 172", "C177" to "Cessna 177",
        "C182" to "Cessna 182", "C185" to "Cessna 185", "C206" to "Cessna 206", "C208" to "Cessna Caravan",
        "C210" to "Cessna 210",
        "C500" to "Cessna Citation", "C550" to "Cessna Citation", "C560" to "Cessna Citation",
        "C750" to "Cessna Citation Ten", "C56X" to "Cessna Citation Excel",
        // Piper
        "PA24" to "Piper Comanche", "PA28" to "Piper Cherokee", "PA30" to "Piper Twin Comanche",
        "PA31" to "Piper Navajo", "PA32" to "Piper Cherokee Six", "PA34" to "Piper Seneca",
        "PA38" to "Piper Tomahawk", "PA44" to "Piper Seminole", "PA46" to "Piper Malibu",
        // Beechcraft
        "BE20" to "Beechcraft King Air 200", "B350" to "Beechcraft King Air 350",
        "BE58" to "Beechcraft Baron", "BE76" to "Beechcraft Duchess", "BE95" to "Beechcraft Travel Air",
        "BE35" to "Beechcraft Bonanza", "A36" to "Beechcraft Bonanza",
        // Other piston / GA
        "SR20" to "Cirrus SR20", "SR22" to "Cirrus SR22", "SR22T" to "Cirrus SR22",
        "DA20" to "Diamond Katana", "DA40" to "Diamond Star", "DA42" to "Diamond Twin Star",
        "M20P" to "Mooney M20", "M20R" to "Mooney M20", "M20U" to "Mooney M20",
        "P68" to "Partenavia P 68",
        // Turboprop / business
        "PC12" to "Pilatus PC 12", "PC24" to "Pilatus PC 24", "P180" to "Piaggio Avanti",
        "TBM7" to "TBM 700", "TBM8" to "TBM 850", "TBM9" to "TBM 900",
        "LR24" to "Learjet 24", "LR31" to "Learjet 31", "LR35" to "Learjet 35", "LR45" to "Learjet 45",
        "FA7X" to "Dassault Falcon 7X", "F2TH" to "Dassault Falcon 2000", "F900" to "Dassault Falcon 900",
        "H25B" to "Hawker 800", "CL60" to "Bombardier Challenger",
        "GLF4" to "Gulfstream", "GLF5" to "Gulfstream", "GL5T" to "Gulfstream",
        "GL6T" to "Gulfstream", "GLEX" to "Gulfstream",
        // Helicopters
        "R22" to "Robinson R22", "R44" to "Robinson R44", "R66" to "Robinson R66",
        "B06" to "Bell 206", "B212" to "Bell 212", "B222" to "Bell 222",
        "B407" to "Bell 407", "B412" to "Bell 412", "B427" to "Bell 427", "B429" to "Bell 429", "B430" to "Bell 430",
        "AS50" to "Eurocopter AStar", "AS55" to "Eurocopter TwinStar",
        "EC30" to "Airbus H130", "EC35" to "Airbus H135", "EC45" to "Airbus H145",
        "A109" to "Agusta A109", "A139" to "AgustaWestland 139",
        "S70" to "Sikorsky Black Hawk", "H60" to "Sikorsky Black Hawk",
        "S76" to "Sikorsky S76", "S92" to "Sikorsky S92",
        "CH47" to "Chinook helicopter", "KMAX" to "K-Max helicopter",
        // Military
        "F15" to "F15 fighter", "F16" to "F16 fighter", "F18" to "F18 fighter", "F35" to "F35 fighter",
        "C17" to "Boeing C17 cargo", "C130" to "Hercules transport",
        // Unclassified
        "GLID" to "glider", "BALL" to "balloon", "ULAC" to "ultralight aircraft",
    )

    /** Full spoken name for the designator, or null when unknown. */
    fun spoken(type: String?): String? =
        type?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()?.let { NAMES[it] }
}