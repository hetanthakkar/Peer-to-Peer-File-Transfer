import pandas as pd
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from email.mime.application import MIMEApplication
import smtplib
import time
import random
from pathlib import Path
import json


def load_checkpoint():
    """Load the last processed index from checkpoint file."""
    try:
        with open("email_checkpoint.json", "r") as f:
            return json.load(f)
    except FileNotFoundError:
        return {"last_processed_index": 0, "sent_emails": []}


def save_checkpoint(index, sent_emails):
    """Save the current progress to checkpoint file."""
    checkpoint = {"last_processed_index": index, "sent_emails": sent_emails}
    with open("email_checkpoint.json", "w") as f:
        json.dump(checkpoint, f)


def generate_email_variations(name):
    """Generate possible email variations for a given name."""
    name_parts = name.lower().split()
    if len(name_parts) < 1:
        return []

    first_name = name_parts[0]
    last_name = name_parts[-1] if len(name_parts) > 1 else ""

    email_patterns = [
        f"{first_name}@stripe.com",
        f"{first_name[0]}{last_name}@stripe.com",
        f"{first_name}.{last_name}@stripe.com",
    ]

    return email_patterns


def create_personalized_email1(
    recruiter_name: str,
    company_name,
    role_type="software engineering",
    include_links: bool = True,
) -> str:
    """
    Create a personalized email message for recruiters.

    Args:
        recruiter_name: Full name of the recruiter
        company_name: Name of the company (optional)
        role_type: Type of role interested in (default: software engineering)
        include_links: Whether to include portfolio links (default: True)

    Returns:
        str: Formatted HTML email content
    """
    recruiter_first_name = recruiter_name.split()[0]

    greetings = [
        f"Hi {recruiter_first_name},",
        f"Hello {recruiter_first_name},",
        f"Dear {recruiter_first_name},",
    ]

    company_specific = ""
    if company_name:
        company_mentions = [
            f"I believe my background aligns well with Software Engineering roles and would love to contribute to your team.",
        ]
        company_specific = f"<p>{random.choice(company_mentions)}</p>"

    achievement_metrics = [
        {
            "category": "Backend Development",
            "details": "Built high-performance microservices using Spring Boot, handling 100K+ daily users. Created real-time APIs ride tracking for thousands of drivers, making operations smoother and more efficient.",
        },
        {
            "category": "Open Source & Frontend Dev",
            "details": "Contributed to Meta, Microsoft, and Elastic projects, with 10+ merged pull requests improving documentation and fixing critical bugs using React.",
        },
        {
            "category": "Technical Leadership",
            "details": "Led a team of 3 teaching assistants for Advanced Graphics Programming, mentoring 50+ students in C++ and OpenGL optimization techniques.",
        },
        {
            "category": "Innovation",
            "details": "Developed an AI-powered recruitment tool that reduced candidate screening time by 60% through automated profile analysis and validation.",
        },
    ]

    portfolio_links = (
        """
    <p>You can learn more about my work here:</p>
    <ul>
        <li>LinkedIn: <a href="https://linkedin.com/in/hetan-thakkar">linkedin.com/in/hetan-thakkar</a></li>
    </ul>
    """
        if include_links
        else ""
    )

    call_to_actions = [
        "Would you be open to a brief conversation about how I could contribute to your team?",
        "I'd welcome the opportunity to discuss how my skills align with your needs.",
        "I'd appreciate the chance to learn more about your current opportunities.",
    ]

    signatures = [
        "Best regards,<br>Hetan",
        "Thank you for your consideration,<br>Hetan",
        "Looking forward to connecting,<br>Hetan",
    ]

    achievements_html = "".join(
        [
            f'<li><b>{achievement["category"]}:</b> {achievement["details"]}</li>'
            for achievement in achievement_metrics
        ]
    )

    email_body = f"""<html>
<body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
    <p>{random.choice(greetings)}</p>
        
    <p>I'm Hetan, a Computer Science Master's student at Northeastern University (graduating May 2025) with over 2 years of professional software development experience.</p>
    
    {company_specific}
    
    <p>Here are some highlights from my background:</p>
    <ul>
    {achievements_html}
    </ul>
    
    {portfolio_links}
    
    <p>{random.choice(call_to_actions)}</p>
    
    <p>I've attached my resume for your review. Thank you for your time.</p>
    
    <p>{random.choice(signatures)}</p>
</body>
</html>"""

    return email_body


def send_email(
    sender_email, sender_password, recipient_email, subject, body, resume_path
):
    """Send an email with attachment."""
    try:
        msg = MIMEMultipart()
        msg["From"] = f"Hetan Thakkar <{sender_email}>"
        msg["To"] = recipient_email
        msg["Subject"] = subject

        html_part = MIMEText(body, "html", "utf-8")
        msg.attach(html_part)

        if resume_path and Path(resume_path).exists():
            with open(resume_path, "rb") as file:
                resume = MIMEApplication(file.read(), _subtype="pdf")
                resume.add_header(
                    "Content-Disposition",
                    "attachment",
                    filename="Hetan-Thakkar-Resume.pdf",
                )
                msg.attach(resume)

        with smtplib.SMTP("smtp.gmail.com", 587) as server:
            server.starttls()
            server.login(sender_email, sender_password)
            server.send_message(msg)

        return True
    except Exception as e:
        print(f"Error sending email to {recipient_email}: {str(e)}")
        return False


def main():
    # Configuration
    SENDER_EMAIL = "hetanthakkar3@gmail.com"
    SENDER_PASSWORD = "lfub fzfj kgqc fqgc"
    RESUME_PATH = "/Users/hetanthakkar/Downloads/Hetan-Thakkar-Resume.pdf"

    # Load checkpoint
    checkpoint = load_checkpoint()
    start_index = checkpoint["last_processed_index"]
    sent_emails = checkpoint["sent_emails"]

    print(f"Resuming from index {start_index}")

    # Load the CSV file with LinkedIn profiles
    df = pd.read_csv("linkedin_profiles_final.csv")

    # Process each profile starting from the checkpoint
    for index, row in df.iloc[start_index:].iterrows():
        recruiter_name = row["name"]
        title = row["title"]
        company = "stripe"

        print(f"Processing recruiter: {recruiter_name} ({title} at {company})")

        # Generate possible email addresses
        email_variations = generate_email_variations(recruiter_name)
        print(email_variations)
        # Create personalized message
        email_content = create_personalized_email1(recruiter_name, "Stripe")

        # Try each email variation
        for email in email_variations:
            print(f"Attempting to send email to {email}...")

            if send_email(
                SENDER_EMAIL,
                SENDER_PASSWORD,
                email,
                "Application for SDE Role at stripe",
                email_content,
                RESUME_PATH,
            ):
                sent_email = {
                    "recruiter_name": recruiter_name,
                    "email": email,
                    "title": title,
                    "company": company,
                    "status": "sent",
                }
                sent_emails.append(sent_email)
                print(f"Successfully sent email to {recruiter_name} at {email}")

                # Save progress after each successful send
                save_checkpoint(index + 1, sent_emails)

            # Random delay between 1 to 10 minutes
            delay_minutes = random.uniform(30, 60)
            print(f"Waiting {delay_minutes:.2f} minutes before next email...")
            time.sleep(delay_minutes)

    # Save final results to CSV
    results_df = pd.DataFrame(sent_emails)
    results_df.to_csv("email_sending_results.csv", index=False)
    print(f"Email campaign completed. Results saved to email_sending_results.csv")


if __name__ == "__main__":
    main()
